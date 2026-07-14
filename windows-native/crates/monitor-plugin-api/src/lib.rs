#![forbid(unsafe_code)]

pub const MONITOR_PLUGIN_ABI_VERSION: u32 = 1;

#[repr(C)]
#[derive(Clone, Copy, Debug, Default)]
pub struct MonitorFrame {
    pub d3d11_texture: *mut core::ffi::c_void,
    pub timestamp_100ns: i64,
    pub width: u32,
    pub height: u32,
    pub format_fourcc: u32,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, Default)]
pub struct MonitorDetection {
    pub x1: f32,
    pub y1: f32,
    pub x2: f32,
    pub y2: f32,
    pub confidence: f32,
    pub track_id: u64,
}

pub type PluginAbiVersion = extern "C" fn() -> u32;
pub type PluginCreate = extern "C" fn() -> *mut core::ffi::c_void;
pub type PluginDestroy = extern "C" fn(*mut core::ffi::c_void);
pub type PluginProcess = extern "C" fn(
    *mut core::ffi::c_void,
    *const MonitorFrame,
    *mut MonitorDetection,
    usize,
) -> usize;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn abi_version_is_stable() {
        assert_eq!(MONITOR_PLUGIN_ABI_VERSION, 1);
        assert!(core::mem::size_of::<MonitorFrame>() >= 32);
    }
}
