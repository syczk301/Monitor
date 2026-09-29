# Camera Monitor

Camera Monitor is a native Windows monitoring and continuous-recording application written in Rust. The production runtime is a single `CameraMonitor.exe` and does not start Python, OpenCV, FFmpeg, Node, .NET, or an AI model.

## Repository layout

- `windows-native/` — Rust workspace, embedded web interface, tray resources, MSI source, and release artifacts.
- `android-app/` — Android client source.
- `scripts/` — Caddy and Tailscale external-access helpers.
- `data/monitor.db` — retained only for one-time migration of historical data.
- `Caddyfile` — authenticated reverse proxy from port 8080 to the native service on port 8000.

## Version control

Development continues on `main`. Formal releases use immutable `vX.Y.Z` tags
and GitHub Releases, such as `v4.8.0` and `v4.8.1`; a permanent branch is not
created for each version. Short-lived feature branches can be merged into
`main` and deleted after their changes are retained there.

Earlier version branch snapshots are retained by existing version tags or
`archive/<old-branch-name>` tags. Archive tags preserve historical code and
do not represent new published releases.

## Build and test

```powershell
cd windows-native
cargo test --workspace
cargo build --release -p monitor-app
```

The native service listens on `127.0.0.1:8000`. Runtime settings, logs, and the active SQLite database are stored under `%LOCALAPPDATA%\CameraMonitor`; recordings default to `F:\monitor`.

For an isolated development launch, `CAMERA_MONITOR_BIND`, `CAMERA_MONITOR_MODE`, and `CAMERA_MONITOR_RECORDING_ROOT` override settings for that process without rewriting the saved configuration.
