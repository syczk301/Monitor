use std::path::Path;
use windows::Win32::System::Com::{COINIT_MULTITHREADED, CoInitializeEx, CoUninitialize};

fn main() -> anyhow::Result<()> {
    unsafe { CoInitializeEx(None, COINIT_MULTITHREADED) }.ok()?;
    let mut failed = false;
    for path in std::env::args().skip(1) {
        match monitor_media::validate_recording(Path::new(&path)) {
            Ok(()) => println!("PASS {path}"),
            Err(error) => {
                eprintln!("FAIL {path}: {error:#}");
                failed = true;
            }
        }
    }
    unsafe { CoUninitialize() };
    anyhow::ensure!(!failed, "one or more recordings failed validation");
    Ok(())
}
