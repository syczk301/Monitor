# Camera Monitor

Camera Monitor is a native Windows monitoring and continuous-recording application written in Rust. The production runtime is a single `CameraMonitor.exe` and does not start Python, OpenCV, FFmpeg, Node, .NET, or an AI model.

The companion Android app provides live video, audio and recording access, with embedded ZeroTier and Tailscale connections.

## Download — 4.9.6

Windows and Android are published together on the [same release page](https://github.com/syczk301/Monitor/releases/latest).

| Platform | Download | Update from an installed version |
| --- | --- | --- |
| Windows x64 | [MSI installer](https://github.com/syczk301/Monitor/releases/download/v4.9.6/CameraMonitor-4.9.6-x64.msi) | Tray menu → Check for updates |
| Android | [APK](https://github.com/syczk301/Monitor/releases/download/v4.9.6/monitor-4.9.6-release.apk) | Settings → Check for updates |

Both clients use version **4.9.6**. The EXE and SHA-256 attachments on the release page support Windows automatic updates; the APK and its SHA-256 file support Android updates. Older platform-specific releases remain available as history.

## Repository layout

- `windows-native/` — Rust workspace, embedded web interface, tray resources, MSI source, and release artifacts.
- `android-app/` — Android client source.
- `scripts/` — Caddy and Tailscale external-access helpers.
- `data/monitor.db` — retained only for one-time migration of historical data.
- `Caddyfile` — authenticated reverse proxy from port 8080 to the native service on port 8000.

## Version control

Development continues on `main`. Formal releases use immutable version tags
and GitHub Releases; a permanent branch is not
created for each version. Short-lived feature branches can be merged into
`main` and deleted after their changes are retained there.

Earlier version branch snapshots are retained by existing version tags or
`archive/<old-branch-name>` tags. Archive tags preserve historical code and
do not represent new published releases.

New releases publish Windows and Android together at the same version. See [release packaging rules](docs/releases.md) for required assets and compatibility with older clients. The unified 4.9.6 release uses `v4.9.6`, preserving the existing Android-only `4.9.6` tag and its download links. Subsequent unified releases use numeric `X.Y.Z` tags.

## Build and test

```powershell
cd windows-native
cargo test --workspace
cargo build --release -p monitor-app
```

The native service listens on `127.0.0.1:8000`. Runtime settings, logs, and the active SQLite database are stored under `%LOCALAPPDATA%\CameraMonitor`; recordings default to `F:\monitor`.

For an isolated development launch, `CAMERA_MONITOR_BIND`, `CAMERA_MONITOR_MODE`, and `CAMERA_MONITOR_RECORDING_ROOT` override settings for that process without rewriting the saved configuration.
