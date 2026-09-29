use windows::{
    Win32::{
        Foundation::{ERROR_FILE_NOT_FOUND, ERROR_PATH_NOT_FOUND},
        System::Registry::{
            HKEY_CURRENT_USER, REG_ROUTINE_FLAGS, RRF_RT_REG_BINARY, RRF_RT_REG_SZ, RegGetValueW,
        },
    },
    core::{PCWSTR, w},
};

// Read each time the menu opens: Windows startup settings can change while we run.
pub fn menu_label() -> &'static str {
    read_status().unwrap_or("开机启动：状态未知")
}

fn read_status() -> Result<&'static str, ()> {
    let run = read_value(
        w!("Software\\Microsoft\\Windows\\CurrentVersion\\Run"),
        RRF_RT_REG_SZ,
    )?;
    let Some(run) = run else {
        return Ok("开机启动：未开启");
    };
    if run.len() % 2 != 0 {
        return Err(());
    }
    let units: Vec<u16> = run
        .chunks_exact(2)
        .map(|b| u16::from_le_bytes([b[0], b[1]]))
        .collect();
    let command = String::from_utf16(&units).map_err(|_| ())?;
    let current = std::env::current_exe().map_err(|_| ())?;
    let approved = read_value(
        w!("Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\StartupApproved\\Run"),
        RRF_RT_REG_BINARY,
    )?;
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
    let command = command.trim_end_matches('\0').trim();
    if command.is_empty() {
        return "开机启动：未开启";
    }
    let target = command
        .strip_prefix('"')
        .and_then(|s| s.strip_suffix('"'))
        .unwrap_or(command);
    if !target
        .replace('/', "\\")
        .eq_ignore_ascii_case(&current.replace('/', "\\"))
    {
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
    use super::classify;

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
