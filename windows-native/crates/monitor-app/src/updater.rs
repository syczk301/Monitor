use anyhow::{Context, Result, bail};
use std::{
    fs,
    os::windows::process::CommandExt,
    process::Command,
    sync::atomic::{AtomicBool, Ordering},
};

static CHECKING: AtomicBool = AtomicBool::new(false);
const SCRIPT: &str = include_str!("../../../scripts/Client-Update.ps1");

pub fn check_for_updates() -> Result<()> {
    if CHECKING.swap(true, Ordering::AcqRel) {
        bail!("更新窗口已打开，请先完成或关闭该窗口");
    }
    let result = std::thread::Builder::new()
        .name("client-update".into())
        .spawn(|| {
            if let Err(error) = run_updater() {
                tracing::error!(%error, "client updater failed");
                crate::native_ui::message_box(&format!("无法启动更新窗口：{error:#}"));
            }
            CHECKING.store(false, Ordering::Release);
        });
    if result.is_err() {
        CHECKING.store(false, Ordering::Release);
    }
    result?;
    Ok(())
}

fn run_updater() -> Result<()> {
    let executable = std::env::current_exe()?;
    let directory =
        std::env::temp_dir().join(format!("CameraMonitor-update-{}", std::process::id()));
    fs::create_dir_all(&directory)?;
    let script = directory.join("Client-Update.ps1");
    // Windows PowerShell 5.1 requires BOM to decode the Chinese UI correctly.
    let mut content = vec![0xef, 0xbb, 0xbf];
    content.extend_from_slice(SCRIPT.trim_start_matches('\u{feff}').as_bytes());
    fs::write(&script, content)?;
    let powershell =
        std::path::PathBuf::from(std::env::var_os("SystemRoot").context("SystemRoot missing")?)
            .join("System32/WindowsPowerShell/v1.0/powershell.exe");
    let status = Command::new(powershell)
        .args(["-NoProfile", "-STA", "-ExecutionPolicy", "Bypass", "-File"])
        .arg(&script)
        .arg("-CurrentExe")
        .arg(&executable)
        .arg("-CurrentVersion")
        .arg(env!("CARGO_PKG_VERSION"))
        .arg("-ParentId")
        .arg(std::process::id().to_string())
        .creation_flags(0x08000000) // Hide console; the updater owns its progress dialog.
        .status()
        .context("starting Windows PowerShell")?;
    if !status.success() {
        tracing::warn!(code = ?status.code(), "updater exited with an error; see update.log");
    }
    Ok(())
}
