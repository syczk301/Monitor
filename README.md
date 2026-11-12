# Camera Monitor

Camera Monitor is a native Windows monitoring and continuous-recording application written in Rust. The production runtime is a single `CameraMonitor.exe` and does not start Python, OpenCV, FFmpeg, Node, .NET, or an AI model.

The companion Android app provides live video, audio and recording access, with embedded ZeroTier and Tailscale connections.

## Download — Windows 4.9.7 / Android 4.9.11

The current Windows installer is on the [latest unified release](https://github.com/syczk301/Monitor/releases/latest). The Android native-network fix is on the [4.9.11 release page](https://github.com/syczk301/Monitor/releases/tag/4.9.11).

| Platform | Download | Update from an installed version |
| --- | --- | --- |
| Windows x64 | [MSI installer](https://github.com/syczk301/Monitor/releases/download/4.9.7/CameraMonitor-4.9.7-x64.msi) | Tray menu → Check for updates |
| Android | [APK](https://github.com/syczk301/Monitor/releases/download/4.9.11/monitor-4.9.11-release.apk) | Settings → Check for updates |

Windows remains at **4.9.7**. Android **4.9.11** repairs embedded ZeroTier JNI/socket handling and Tailscale physical-network binding, with expanded receive-path diagnostics. The problem phone's FPS still needs verification; emulator results do not establish remote overlay performance. EXE and APK SHA-256 attachments support their respective automatic updaters.

Version 4.9.7 learns the computer's ZeroTier and Tailscale server addresses and selects the matching address when switching networks. Upgrade both clients, then connect through one working address to learn the other route. See [setup and manual override](android-app/docs/adaptive-network-addresses.md).

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

Unified releases publish Windows and Android together at the same version. Android-only fixes keep the Windows Latest release unchanged. See [release packaging rules](docs/releases.md) for required assets and compatibility with older clients. The unified 4.9.6 release uses `v4.9.6`, preserving the existing Android-only `4.9.6` tag and its download links. Subsequent releases use numeric `X.Y.Z` tags.

## Build and test

```powershell
cd windows-native
cargo test --workspace
cargo build --release -p monitor-app
```

The native service listens on `127.0.0.1:8000`. Runtime settings, logs, and the active SQLite database are stored under `%LOCALAPPDATA%\CameraMonitor`; recordings default to `F:\monitor`.

For an isolated development launch, `CAMERA_MONITOR_BIND`, `CAMERA_MONITOR_MODE`, and `CAMERA_MONITOR_RECORDING_ROOT` override settings for that process without rewriting the saved configuration.
