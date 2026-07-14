# Camera Monitor Native

Single-process Rust/Windows implementation of the camera monitor. It embeds the
existing web dashboard, records through Windows Media Capture, and does not
start Python, OpenCV, FFmpeg, Node, or .NET processes.

## Development

```powershell
cargo test --workspace
cargo run -p monitor-app
```

Runtime data is stored below `%LOCALAPPDATA%\CameraMonitor`; recordings default
to `F:\monitor`.

For an isolated development launch, `CAMERA_MONITOR_BIND`,
`CAMERA_MONITOR_MODE`, and `CAMERA_MONITOR_RECORDING_ROOT` override the saved
settings for that process without rewriting the user's configuration.
