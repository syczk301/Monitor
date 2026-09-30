use windows::{
    Win32::{
        Foundation::{ERROR_FILE_NOT_FOUND, ERROR_PATH_NOT_FOUND},
        System::Registry::{
            HKEY_CURRENT_USER, REG_BINARY, REG_ROUTINE_FLAGS, REG_SZ, RRF_RT_REG_BINARY,
            RRF_RT_REG_SZ, RegDeleteKeyValueW, RegGetValueW, RegSetKeyValueW,
        },
    },
    core::{PCWSTR, w},
};

const RUN_KEY: PCWSTR = w!("Software\\Microsoft\\Windows\\CurrentVersion\\Run");
const APPROVED_KEY: PCWSTR =
    w!("Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\StartupApproved\\Run");

pub fn toggle() -> anyhow::Result<()> {
    let enabled =
        read_status().map_err(|_| anyhow::anyhow!("读取开机启动状态失败"))? == "开机启动：已开启";
    let current = std::env::current_exe()?;
    set_enabled_at(RUN_KEY, APPROVED_KEY, &current.to_string_lossy(), !enabled)
}

fn set_enabled_at(
    run_key: PCWSTR,
    approved_key: PCWSTR,
    current: &str,
    enabled: bool,
) -> anyhow::Result<()> {
    if enabled {
        // Explicit user opt-in also clears Task Manager's disabled state.
        let mut approved = [0u8; 12];
        approved[0] = 2;
        unsafe {
            RegSetKeyValueW(
                HKEY_CURRENT_USER,
                approved_key,
                w!("CameraMonitor"),
                REG_BINARY.0,
                Some(approved.as_ptr().cast()),
                approved.len() as u32,
            )
            .ok()?;
        }
        let command: Vec<u16> = format!("\"{}\"", normalized_path(current))
            .encode_utf16()
            .chain(Some(0))
            .collect();
        unsafe {
            RegSetKeyValueW(
                HKEY_CURRENT_USER,
                run_key,
                w!("CameraMonitor"),
                REG_SZ.0,
                Some(command.as_ptr().cast()),
                (command.len() * 2) as u32,
            )
            .ok()?;
        }
    } else {
        let result = unsafe { RegDeleteKeyValueW(HKEY_CURRENT_USER, run_key, w!("CameraMonitor")) };
        if result != ERROR_FILE_NOT_FOUND && result != ERROR_PATH_NOT_FOUND {
            result.ok()?;
        }
    }
    Ok(())
}

// Only migrate an existing registration. An absent entry or a Windows-disabled
// StartupApproved record is the user's choice and must remain unchanged.
pub fn repair_existing() -> anyhow::Result<bool> {
    let current = std::env::current_exe()?;
    repair_at(RUN_KEY, &current.to_string_lossy())
}

fn repair_at(key: PCWSTR, current: &str) -> anyhow::Result<bool> {
    let existing =
        read_value(key, RRF_RT_REG_SZ).map_err(|_| anyhow::anyhow!("读取开机启动项失败"))?;
    let Some(existing) = existing else {
        return Ok(false);
    };
    let command = decode_command(&existing).map_err(|_| anyhow::anyhow!("开机启动项格式无效"))?;
    let Some((target, arguments)) = command_target(&command) else {
        return Ok(false);
    };
    if same_path(target, current) {
        return Ok(false);
    }
    let command = format!("\"{}\"{}", normalized_path(current), arguments);
    let data: Vec<u16> = command.encode_utf16().chain(Some(0)).collect();
    unsafe {
        RegSetKeyValueW(
            HKEY_CURRENT_USER,
            key,
            w!("CameraMonitor"),
            REG_SZ.0,
            Some(data.as_ptr().cast()),
            (data.len() * 2) as u32,
        )
        .ok()?;
    }
    Ok(true)
}

fn decode_command(data: &[u8]) -> Result<String, ()> {
    if data.len() % 2 != 0 {
        return Err(());
    }
    let units: Vec<u16> = data
        .chunks_exact(2)
        .map(|b| u16::from_le_bytes([b[0], b[1]]))
        .collect();
    String::from_utf16(&units).map_err(|_| ())
}

fn command_target(command: &str) -> Option<(&str, &str)> {
    let command = command.trim_end_matches('\0').trim();
    if command.is_empty() {
        return None;
    }
    if let Some(quoted) = command.strip_prefix('"') {
        let end = quoted.find('"')?;
        return Some((&quoted[..end], &quoted[end + 1..]));
    }
    // Legacy entries may contain unquoted paths with spaces.
    if let Some(end) = command.to_ascii_lowercase().find(".exe") {
        return Some((&command[..end + 4], &command[end + 4..]));
    }
    Some((command, ""))
}

fn normalized_path(path: &str) -> String {
    let path = path.replace('/', "\\");
    if let Some(unc) = path.strip_prefix(r"\\?\UNC\") {
        format!(r"\\{unc}")
    } else {
        path.strip_prefix(r"\\?\").unwrap_or(&path).to_owned()
    }
}

fn same_path(target: &str, current: &str) -> bool {
    normalized_path(target).eq_ignore_ascii_case(&normalized_path(current))
}

// Read each time the menu opens: Windows startup settings can change while we run.
pub fn menu_label() -> &'static str {
    read_status().unwrap_or("开机启动：状态未知")
}

fn read_status() -> Result<&'static str, ()> {
    let run = read_value(RUN_KEY, RRF_RT_REG_SZ)?;
    let Some(run) = run else {
        return Ok("开机启动：未开启");
    };
    let command = decode_command(&run)?;
    let current = std::env::current_exe().map_err(|_| ())?;
    let approved = read_value(APPROVED_KEY, RRF_RT_REG_BINARY)?;
    Ok(classify(
        &command,
        &current.to_string_lossy(),
        approved.as_deref(),
    ))
}

fn read_value(key: PCWSTR, flags: REG_ROUTINE_FLAGS) -> Result<Option<Vec<u8>>, ()> {
    let mut data = vec![0u8; 4096];
    let mut size = data.len() as u32;
    let result = unsafe {
        RegGetValueW(
            HKEY_CURRENT_USER,
            key,
            w!("CameraMonitor"),
            flags,
            None,
            Some(data.as_mut_ptr().cast()),
            Some(&mut size),
        )
    };
    if result == ERROR_FILE_NOT_FOUND || result == ERROR_PATH_NOT_FOUND {
        return Ok(None);
    }
    result.ok().map_err(|_| ())?;
    data.truncate(size as usize);
    Ok(Some(data))
}

fn classify(command: &str, current: &str, approved: Option<&[u8]>) -> &'static str {
    if command.trim_end_matches('\0').trim().is_empty() {
        return "开机启动：未开启";
    }
    let Some((target, _)) = command_target(command) else {
        return "开机启动：状态未知";
    };
    if !same_path(target, current) {
        return "开机启动：指向其他版本";
    }
    // Unknown or malformed Windows approval records must not be reported as enabled.
    match approved {
        None => "开机启动：已开启",
        Some(data) if data.len() >= 12 => match u32::from_le_bytes(data[..4].try_into().unwrap()) {
            2 | 6 => "开机启动：已开启",
            3 | 7 => "开机启动：已被系统禁用",
            _ => "开机启动：状态未知",
        },
        _ => "开机启动：状态未知",
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn explicit_toggle_enables_current_exe_and_removes_registration() {
        use windows::Win32::System::Registry::{HKEY, RegCloseKey, RegCreateKeyW, RegDeleteTreeW};
        let key: Vec<u16> = format!(r"Software\CameraMonitorTests\Toggle-{}", std::process::id())
            .encode_utf16()
            .chain(Some(0))
            .collect();
        let key_ptr = PCWSTR(key.as_ptr());
        let mut handle = HKEY::default();
        unsafe {
            RegCreateKeyW(HKEY_CURRENT_USER, key_ptr, &mut handle)
                .ok()
                .unwrap();
        }
        struct Cleanup(Vec<u16>, HKEY);
        impl Drop for Cleanup {
            fn drop(&mut self) {
                unsafe {
                    let _ = RegCloseKey(self.1);
                    let _ = RegDeleteTreeW(HKEY_CURRENT_USER, PCWSTR(self.0.as_ptr()));
                }
            }
        }
        let _cleanup = Cleanup(key.clone(), handle);
        let approved: Vec<u16> = format!(
            r"{}\Approved",
            String::from_utf16(&key[..key.len() - 1]).unwrap()
        )
        .encode_utf16()
        .chain(Some(0))
        .collect();
        let approved_ptr = PCWSTR(approved.as_ptr());
        let current = r"\\?\D:\Program Files\monitor\CameraMonitor.exe";
        set_enabled_at(key_ptr, approved_ptr, current, true).unwrap();
        let command =
            decode_command(&read_value(key_ptr, RRF_RT_REG_SZ).unwrap().unwrap()).unwrap();
        assert_eq!(
            command.trim_end_matches('\0'),
            r#""D:\Program Files\monitor\CameraMonitor.exe""#
        );
        let mut disabled = [0u8; 12];
        disabled[0] = 3;
        unsafe {
            RegSetKeyValueW(
                HKEY_CURRENT_USER,
                approved_ptr,
                w!("CameraMonitor"),
                REG_BINARY.0,
                Some(disabled.as_ptr().cast()),
                12,
            )
            .ok()
            .unwrap();
        }
        assert_eq!(
            classify(&command, current, Some(&disabled)),
            "开机启动：已被系统禁用"
        );
        set_enabled_at(key_ptr, approved_ptr, current, true).unwrap();
        let approval = read_value(approved_ptr, RRF_RT_REG_BINARY)
            .unwrap()
            .unwrap();
        assert_eq!(
            classify(&command, current, Some(&approval)),
            "开机启动：已开启"
        );
        set_enabled_at(key_ptr, approved_ptr, current, false).unwrap();
        assert!(read_value(key_ptr, RRF_RT_REG_SZ).unwrap().is_none());
        set_enabled_at(key_ptr, approved_ptr, current, false).unwrap();
    }

    #[test]
    fn arguments_and_extended_paths_are_not_other_versions() {
        let path = r"C:\Program Files\CameraMonitor\CameraMonitor.exe";
        assert_eq!(
            classify(&format!("\"{path}\" --silent"), path, None),
            "开机启动：已开启"
        );
        assert_eq!(
            classify(path, &format!(r"\\?\{path}"), None),
            "开机启动：已开启"
        );
        assert_eq!(classify("\"broken", path, None), "开机启动：状态未知");
    }

    #[test]
    fn repairs_registered_path_without_enabling_missing_or_disabled_startup() {
        use windows::Win32::System::Registry::{HKEY, RegCloseKey, RegCreateKeyW, RegDeleteTreeW};
        let key: Vec<u16> = format!(
            r"Software\CameraMonitorTests\Startup-{}",
            std::process::id()
        )
        .encode_utf16()
        .chain(Some(0))
        .collect();
        let key_ptr = PCWSTR(key.as_ptr());
        let current = r"D:\Program Files\monitor\CameraMonitor.exe";
        let mut handle = HKEY::default();
        unsafe {
            RegCreateKeyW(HKEY_CURRENT_USER, key_ptr, &mut handle)
                .ok()
                .unwrap();
        }
        struct Cleanup(Vec<u16>, HKEY);
        impl Drop for Cleanup {
            fn drop(&mut self) {
                unsafe {
                    let _ = RegCloseKey(self.1);
                    let _ = RegDeleteTreeW(HKEY_CURRENT_USER, PCWSTR(self.0.as_ptr()));
                }
            }
        }
        let _cleanup = Cleanup(key.clone(), handle);
        assert!(!repair_at(key_ptr, current).unwrap());
        assert!(read_value(key_ptr, RRF_RT_REG_SZ).unwrap().is_none());
        let old: Vec<u16> = "\"C:\\old\\CameraMonitor.exe\" --silent"
            .encode_utf16()
            .chain(Some(0))
            .collect();
        unsafe {
            RegSetKeyValueW(
                HKEY_CURRENT_USER,
                key_ptr,
                w!("CameraMonitor"),
                REG_SZ.0,
                Some(old.as_ptr().cast()),
                (old.len() * 2) as u32,
            )
            .ok()
            .unwrap();
        }
        assert!(repair_at(key_ptr, current).unwrap());
        let updated =
            decode_command(&read_value(key_ptr, RRF_RT_REG_SZ).unwrap().unwrap()).unwrap();
        assert_eq!(
            updated.trim_end_matches('\0'),
            format!("\"{current}\" --silent")
        );
        assert!(!repair_at(key_ptr, current).unwrap());
        let mut disabled = [0u8; 12];
        disabled[0] = 3;
        assert_eq!(
            classify(&updated, current, Some(&disabled)),
            "开机启动：已被系统禁用"
        );
    }

    #[test]
    fn checks_target_and_windows_override() {
        let path = r"D:\ProgramFiles\monitor\CameraMonitor.exe";
        assert_eq!(classify("", path, None), "开机启动：未开启");
        assert_eq!(
            classify(&format!("\"{path}\"\0"), path, None),
            "开机启动：已开启"
        );
        assert_eq!(
            classify(&path.to_lowercase(), path, None),
            "开机启动：已开启"
        );
        assert_eq!(
            classify(r"C:\old\CameraMonitor.exe", path, None),
            "开机启动：指向其他版本"
        );
        for (state, label) in [
            (2, "开机启动：已开启"),
            (3, "开机启动：已被系统禁用"),
            (6, "开机启动：已开启"),
            (7, "开机启动：已被系统禁用"),
            (9, "开机启动：状态未知"),
        ] {
            let mut record = [0u8; 12];
            record[0] = state;
            assert_eq!(classify(path, path, Some(&record)), label);
        }
        assert_eq!(classify(path, path, Some(&[2])), "开机启动：状态未知");
    }
}
