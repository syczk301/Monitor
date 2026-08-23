#![windows_subsystem = "windows"]

mod native_ui;

use anyhow::Result;
use monitor_media::MediaService;
use monitor_storage::{
    AppPaths, RecordingMode, Repository, load_or_create_settings, migrate_legacy,
    save_settings_atomic,
};
use monitor_web::{AudioHub, PreviewHub, WebState};
use std::{
    env,
    path::{Path, PathBuf},
    sync::{Arc, RwLock},
};
use tokio::sync::watch;
use tracing_subscriber::{EnvFilter, layer::SubscriberExt, util::SubscriberInitExt};

fn main() -> Result<()> {
    let paths = AppPaths::discover()?;
    paths.ensure()?;
    let file_appender = tracing_appender::rolling::daily(&paths.logs, "monitor-native.log");
    let (writer, _guard) = tracing_appender::non_blocking(file_appender);
    tracing_subscriber::registry()
        .with(
            EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| EnvFilter::new("info,tower_http=warn")),
        )
        .with(
            tracing_subscriber::fmt::layer()
                .with_writer(writer)
                .with_ansi(false),
        )
        .init();

    let repository_root = find_legacy_root().unwrap_or_else(|| PathBuf::from(r"D:\mywork\monitor"));
    migrate_legacy(&paths, &repository_root)?;
    let mut settings = load_or_create_settings(&paths)?;
    if let Some(address) =
        env::args().find_map(|argument| argument.strip_prefix("--set-bind=").map(ToOwned::to_owned))
    {
        address.parse::<std::net::SocketAddr>()?;
        settings.bind_address = address;
        save_settings_atomic(&paths, &settings)?;
        return Ok(());
    }
    apply_environment_overrides(&mut settings);
    migrate_remote_nodes_to_https(&mut settings, &paths)?;
    let dashboard_url = dashboard_url(&settings.bind_address);
    let recording_root = settings.recording_root.clone();
    let repository = Repository::open(paths.database.clone())?;
    let preview = PreviewHub::new();
    let preview_sink = preview.clone();
    let audio = AudioHub::new();
    let audio_sink = audio.clone();
    let mut media = MediaService::start(
        settings.clone(),
        Some(Arc::new(move |jpeg| preview_sink.publish(jpeg))),
        Some(Arc::new(move |pcm| audio_sink.publish(pcm))),
    )?;
    let runtime_status = media.status();
    let controller = media.controller();
    let settings = Arc::new(RwLock::new(settings));
    let (shutdown_tx, shutdown_rx) = watch::channel(false);

    let web_state = WebState {
        settings,
        paths: paths.clone(),
        repository,
        runtime: runtime_status,
        media: controller.clone(),
        preview,
        audio,
    };
    let web_thread = std::thread::Builder::new()
        .name("monitor-web-host".into())
        .spawn(move || -> Result<()> {
            let runtime = tokio::runtime::Builder::new_multi_thread()
                .enable_all()
                .thread_name("monitor-async")
                .build()?;
            runtime.block_on(monitor_web::serve(web_state, shutdown_rx))
        })?;

    tracing::info!("CameraMonitor Rust native runtime started");
    native_ui::run_tray(controller, recording_root, dashboard_url)?;
    let _ = shutdown_tx.send(true);
    match web_thread.join() {
        Ok(result) => result?,
        Err(_) => tracing::error!("web host thread panicked"),
    }
    media.shutdown();
    tracing::info!("CameraMonitor stopped cleanly");
    Ok(())
}

fn dashboard_url(bind_address: &str) -> String {
    match bind_address.parse::<std::net::SocketAddr>() {
        Ok(address) if address.ip().is_unspecified() => {
            format!("https://127.0.0.1:{}", address.port())
        }
        _ => format!("https://{bind_address}"),
    }
}

fn migrate_remote_nodes_to_https(
    settings: &mut monitor_storage::Settings,
    paths: &AppPaths,
) -> Result<()> {
    let mut changed = false;
    for node in &mut settings.remote_nodes {
        if let Some(address) = node.address.strip_prefix("http://") {
            node.address = format!("https://{}", address.trim());
            changed = true;
        } else if !node.address.contains("://") {
            node.address = format!("https://{}", node.address.trim());
            changed = true;
        }
    }
    if changed {
        save_settings_atomic(paths, settings)?;
    }
    Ok(())
}

fn apply_environment_overrides(settings: &mut monitor_storage::Settings) {
    if let Ok(address) = env::var("CAMERA_MONITOR_BIND") {
        settings.bind_address = address;
    }
    if let Ok(root) = env::var("CAMERA_MONITOR_RECORDING_ROOT") {
        settings.recording_root = PathBuf::from(root);
    }
    if let Ok(mode) = env::var("CAMERA_MONITOR_MODE") {
        settings.recording_mode = if mode.eq_ignore_ascii_case("off") {
            RecordingMode::Off
        } else if mode.eq_ignore_ascii_case("schedule") {
            RecordingMode::Schedule
        } else {
            RecordingMode::Continuous
        };
    }
}

fn find_legacy_root() -> Option<PathBuf> {
    let mut candidates = Vec::new();
    if let Some(root) = env::var_os("CAMERA_MONITOR_ROOT") {
        candidates.push(PathBuf::from(root));
    }
    candidates.push(env::current_dir().ok()?);
    if let Ok(executable) = env::current_exe() {
        if let Some(parent) = executable.parent() {
            candidates.push(parent.to_path_buf());
        }
    }
    for candidate in candidates {
        for parent in candidate.ancestors().take(8) {
            if is_legacy_root(parent) {
                return Some(parent.to_path_buf());
            }
        }
    }
    None
}

fn is_legacy_root(path: &Path) -> bool {
    path.join("data").join("monitor.db").is_file()
}
