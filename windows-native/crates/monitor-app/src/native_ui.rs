use anyhow::{Result, bail};
use monitor_media::{MediaController, RuntimeStatus};
use monitor_storage::RecordingMode;
use std::{
    mem::size_of,
    path::PathBuf,
    sync::{Arc, Mutex, OnceLock, RwLock},
};
use windows::{
    Win32::{
        Foundation::{
            CloseHandle, ERROR_ALREADY_EXISTS, GetLastError, HWND, LPARAM, LRESULT, POINT, WPARAM,
        },
        Graphics::Gdi::HBRUSH,
        System::{LibraryLoader::GetModuleHandleW, Threading::CreateMutexW},
        UI::{
            Shell::{
                NIF_ICON, NIF_MESSAGE, NIF_TIP, NIM_ADD, NIM_DELETE, NOTIFYICONDATAW,
                Shell_NotifyIconW, ShellExecuteW,
            },
            WindowsAndMessaging::{
                AppendMenuW, CREATESTRUCTW, CS_HREDRAW, CS_VREDRAW, CW_USEDEFAULT, CreatePopupMenu,
                CreateWindowExW, DefWindowProcW, DestroyMenu, DispatchMessageW, GetCursorPos,
                GetMessageW, IDC_ARROW, LoadCursorW, LoadIconW, MB_ICONINFORMATION, MB_OK,
                MF_GRAYED, MF_SEPARATOR, MF_STRING, MSG, MessageBoxW, PostMessageW,
                PostQuitMessage, RegisterClassW, SW_SHOWNORMAL, SetForegroundWindow,
                SetMenuDefaultItem, TPM_RETURNCMD, TPM_RIGHTBUTTON, TrackPopupMenu,
                TranslateMessage, WINDOW_EX_STYLE, WM_APP, WM_COMMAND, WM_DESTROY,
                WM_LBUTTONDBLCLK, WM_NCCREATE, WM_NULL, WM_RBUTTONUP, WNDCLASSW, WS_OVERLAPPED,
            },
        },
    },
    core::{PCWSTR, w},
};

const TRAY_MESSAGE: u32 = WM_APP + 1;
const CMD_DASHBOARD: usize = 1001;
const CMD_STATUS: usize = 1002;
const CMD_RECORDINGS: usize = 1003;
const CMD_START: usize = 1004;
const CMD_STOP: usize = 1005;
const CMD_EXIT: usize = 1006;
const CMD_UPDATE: usize = 1007;

struct UiState {
    media: MediaController,
    runtime: Arc<RwLock<RuntimeStatus>>,
    recording_root: PathBuf,
    dashboard_url: String,
}

static UI_STATE: OnceLock<Mutex<UiState>> = OnceLock::new();

pub fn run_tray(
    media: MediaController,
    runtime: Arc<RwLock<RuntimeStatus>>,
    recording_root: PathBuf,
    dashboard_url: String,
) -> Result<()> {
    let mutex = unsafe {
        CreateMutexW(
            None,
            true,
            w!("Local\\CameraMonitor.RustNative.SingleInstance"),
        )?
    };
    if unsafe { GetLastError() } == ERROR_ALREADY_EXISTS {
        bail!("CameraMonitor is already running");
    }
    UI_STATE
        .set(Mutex::new(UiState {
            media,
            runtime,
            recording_root,
            dashboard_url,
        }))
        .map_err(|_| anyhow::anyhow!("tray state already initialized"))?;

    let instance = unsafe { GetModuleHandleW(None)? };
    let class_name = w!("CameraMonitorRustTrayWindow");
    let class = WNDCLASSW {
        style: CS_HREDRAW | CS_VREDRAW,
        lpfnWndProc: Some(window_proc),
        hInstance: instance.into(),
        hCursor: unsafe { LoadCursorW(None, IDC_ARROW)? },
        hbrBackground: HBRUSH::default(),
        lpszClassName: class_name,
        ..Default::default()
    };
    if unsafe { RegisterClassW(&class) } == 0 {
        bail!("registering tray window class failed");
    }
    let hwnd = unsafe {
        CreateWindowExW(
            WINDOW_EX_STYLE::default(),
            class_name,
            w!("智能监控"),
            WS_OVERLAPPED,
            CW_USEDEFAULT,
            CW_USEDEFAULT,
            0,
            0,
            None,
            None,
            Some(instance.into()),
            None,
        )?
    };
    add_tray_icon(hwnd)?;

    let mut message = MSG::default();
    while unsafe { GetMessageW(&mut message, None, 0, 0) }.as_bool() {
        unsafe {
            let _ = TranslateMessage(&message);
            DispatchMessageW(&message);
        }
    }
    delete_tray_icon(hwnd);
    unsafe { CloseHandle(mutex)? };
    Ok(())
}

fn add_tray_icon(hwnd: HWND) -> Result<()> {
    let instance = unsafe { GetModuleHandleW(None)? };
    let mut data = NOTIFYICONDATAW {
        cbSize: size_of::<NOTIFYICONDATAW>() as u32,
        hWnd: hwnd,
        uID: 1,
        uFlags: NIF_MESSAGE | NIF_ICON | NIF_TIP,
        uCallbackMessage: TRAY_MESSAGE,
        hIcon: unsafe { LoadIconW(Some(instance.into()), PCWSTR(1usize as *const u16))? },
        ..Default::default()
    };
    copy_wide(
        &mut data.szTip,
        &format!(
            "智能监控 v{}\n双击打开监控面板 · 右键显示菜单",
            env!("CARGO_PKG_VERSION")
        ),
    );
    if !unsafe { Shell_NotifyIconW(NIM_ADD, &data) }.as_bool() {
        bail!("adding tray icon failed");
    }
    Ok(())
}

fn delete_tray_icon(hwnd: HWND) {
    let data = NOTIFYICONDATAW {
        cbSize: size_of::<NOTIFYICONDATAW>() as u32,
        hWnd: hwnd,
        uID: 1,
        ..Default::default()
    };
    unsafe {
        let _ = Shell_NotifyIconW(NIM_DELETE, &data);
    }
}

unsafe extern "system" fn window_proc(
    hwnd: HWND,
    message: u32,
    wparam: WPARAM,
    lparam: LPARAM,
) -> LRESULT {
    match message {
        WM_NCCREATE => {
            let _ = lparam.0 as *const CREATESTRUCTW;
            LRESULT(1)
        }
        TRAY_MESSAGE => {
            match lparam.0 as u32 {
                WM_RBUTTONUP => unsafe { show_menu(hwnd) },
                WM_LBUTTONDBLCLK => open_dashboard(),
                _ => {}
            }
            LRESULT(0)
        }
        WM_COMMAND => {
            handle_command(wparam.0 & 0xffff);
            LRESULT(0)
        }
        WM_DESTROY => {
            unsafe { PostQuitMessage(0) };
            LRESULT(0)
        }
        _ => unsafe { DefWindowProcW(hwnd, message, wparam, lparam) },
    }
}

unsafe fn show_menu(hwnd: HWND) {
    let Ok(menu) = (unsafe { CreatePopupMenu() }) else {
        return;
    };
    let heading = wide(&format!("智能监控  v{}", env!("CARGO_PKG_VERSION")));
    let status = runtime_snapshot();
    let startup = wide(crate::startup::menu_label());
    let summary = wide(
        &status
            .as_ref()
            .map(recording_summary)
            .unwrap_or_else(|| "正在读取状态…".into()),
    );
    unsafe {
        let _ = AppendMenuW(menu, MF_STRING | MF_GRAYED, 0, PCWSTR(heading.as_ptr()));
        let _ = AppendMenuW(menu, MF_STRING | MF_GRAYED, 0, PCWSTR(summary.as_ptr()));
        let _ = AppendMenuW(menu, MF_STRING | MF_GRAYED, 0, PCWSTR(startup.as_ptr()));
        let _ = AppendMenuW(menu, MF_SEPARATOR, 0, PCWSTR::null());
        let _ = AppendMenuW(menu, MF_STRING, CMD_DASHBOARD, w!("打开监控面板 (&O)"));
        let _ = SetMenuDefaultItem(menu, CMD_DASHBOARD as u32, 0);
        let _ = AppendMenuW(menu, MF_STRING, CMD_RECORDINGS, w!("打开录像文件夹 (&F)"));
        let _ = AppendMenuW(menu, MF_STRING, CMD_STATUS, w!("查看运行状态 (&S)…"));
        let _ = AppendMenuW(menu, MF_SEPARATOR, 0, PCWSTR::null());
        let _ = AppendMenuW(menu, MF_STRING, CMD_START, w!("切换为持续录像 (&R)"));
        let _ = AppendMenuW(menu, MF_STRING, CMD_STOP, w!("关闭自动录像 (&P)"));
        let _ = AppendMenuW(menu, MF_SEPARATOR, 0, PCWSTR::null());
        let update_flags = if crate::updater::is_checking() {
            MF_STRING | MF_GRAYED
        } else {
            MF_STRING
        };
        let update_text = if crate::updater::is_checking() {
            w!("更新窗口已打开…")
        } else {
            w!("检查更新 (&U)…")
        };
        let _ = AppendMenuW(menu, update_flags, CMD_UPDATE, update_text);
        let _ = AppendMenuW(menu, MF_STRING, CMD_EXIT, w!("退出智能监控 (&X)"));
        let mut point = POINT::default();
        let _ = GetCursorPos(&mut point);
        let _ = SetForegroundWindow(hwnd);
        let command = TrackPopupMenu(
            menu,
            TPM_RIGHTBUTTON | TPM_RETURNCMD,
            point.x,
            point.y,
            None,
            hwnd,
            None,
        );
        let _ = DestroyMenu(menu);
        let _ = PostMessageW(Some(hwnd), WM_NULL, WPARAM(0), LPARAM(0));
        if command.0 != 0 {
            let _ = PostMessageW(
                Some(hwnd),
                WM_COMMAND,
                WPARAM(command.0 as usize),
                LPARAM(0),
            );
        }
    }
}

fn handle_command(command: usize) {
    match command {
        CMD_DASHBOARD => open_dashboard(),
        CMD_STATUS => show_status(),
        CMD_UPDATE => {
            if let Err(error) = crate::updater::check_for_updates() {
                message_box(&format!("检查更新失败：{error:#}"));
            }
        }
        CMD_RECORDINGS => {
            if let Some(state) = UI_STATE.get().and_then(|s| s.lock().ok()) {
                shell_open(&state.recording_root.to_string_lossy());
            }
        }
        CMD_START => set_recording(RecordingMode::Continuous),
        CMD_STOP => set_recording(RecordingMode::Off),
        CMD_EXIT => unsafe { PostQuitMessage(0) },
        _ => {}
    }
}

fn set_recording(mode: RecordingMode) {
    if let Some(state) = UI_STATE.get().and_then(|s| s.lock().ok()) {
        if let Err(error) = state.media.set_mode(mode) {
            message_box(&format!("切换录像失败：{error}"));
        }
    }
}

fn open_dashboard() {
    if let Some(url) = UI_STATE
        .get()
        .and_then(|state| state.lock().ok())
        .map(|state| state.dashboard_url.clone())
    {
        shell_open(&url);
    }
}
fn show_status() {
    let Some(status) = runtime_snapshot() else {
        message_box("暂时无法读取运行状态，请稍后重试。");
        return;
    };
    let mut text = format!(
        "智能监控 v{}\n\n{}\n摄像头：{}\n采集：{} × {} / {:.1} FPS\n预览连接：{}\n声音连接：{}",
        env!("CARGO_PKG_VERSION"),
        recording_summary(&status),
        if status.camera_name.is_empty() {
            "尚未就绪"
        } else {
            &status.camera_name
        },
        status.width,
        status.height,
        status.fps,
        status.preview_clients,
        status.audio_clients,
    );
    if !status.recording_file.is_empty() {
        text.push_str(&format!("\n\n当前录像：\n{}", status.recording_file));
    }
    for error in [&status.last_error, &status.recording_validation_error] {
        if !error.is_empty() {
            text.push_str(&format!("\n\n异常：{error}"));
        }
    }
    message_box(&text);
}

fn runtime_snapshot() -> Option<RuntimeStatus> {
    let state = UI_STATE.get()?.lock().ok()?;
    let status = state.runtime.read().ok()?.clone();
    Some(status)
}

fn recording_summary(status: &RuntimeStatus) -> String {
    if !status.last_error.is_empty() || !status.recording_validation_error.is_empty() {
        "运行异常 · 请查看运行状态".into()
    } else if status.recording_active {
        "正在录像".into()
    } else {
        "当前未录像".into()
    }
}

fn shell_open(value: &str) {
    let wide = wide(value);
    unsafe {
        let _ = ShellExecuteW(
            None,
            w!("open"),
            PCWSTR(wide.as_ptr()),
            None,
            None,
            SW_SHOWNORMAL,
        );
    }
}

pub(crate) fn message_box(message: &str) {
    let message = wide(message);
    unsafe {
        let _ = MessageBoxW(
            None,
            PCWSTR(message.as_ptr()),
            w!("智能监控"),
            MB_OK | MB_ICONINFORMATION,
        );
    }
}

fn wide(value: &str) -> Vec<u16> {
    value.encode_utf16().chain(Some(0)).collect()
}
fn copy_wide<const N: usize>(target: &mut [u16; N], value: &str) {
    for (destination, source) in target.iter_mut().zip(value.encode_utf16().chain(Some(0))) {
        *destination = source;
    }
}
