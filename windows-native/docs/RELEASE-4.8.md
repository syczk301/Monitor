# Camera Monitor 4.8.0

## Changes

- Windows tray menu: **检查更新…** opens a version check and download dialog.
- Updates come from this project's stable GitHub Releases. The updater validates
  the expected asset URLs, file sizes and SHA-256 before stopping the client.
- The current recording is finalized through normal shutdown. A backup is kept
  next to the executable; a failed restart triggers rollback.
- Existing camera settings, recording schedule, TLS certificates, recording files
  and startup preferences are preserved by the updater.
- The web sound button immediately displays **正在开启…** while connecting.
- Earlier recording-session recovery and green-frame validation fixes remain.

## First upgrade

4.7 does not have the update menu. Exit the old tray client and install the
4.8.0 MSI once, then open Camera Monitor from the Start menu. The MSI includes
current-user Windows logon startup. It reuses the existing runtime configuration;
a previously unconfigured computer still needs HTTPS/camera provisioning.

Future upgrades are available through the tray menu after a newer stable release
with both `CameraMonitor.exe` and `CameraMonitor.exe.sha256` is published.
See [UPDATING.md](UPDATING.md) for the publishing and recovery procedure.

## Development validation

- 19 Rust workspace tests passed using an isolated target directory.
- 29 update validation/filesystem checks passed under Windows PowerShell 5.1.
- A hidden Win32 fixture verified real graceful shutdown, executable replacement,
  restart, readiness-failure rollback and preservation of a configuration file.
- The live GitHub query correctly reports `no_release` when no stable release is
  available. Offline/network failures do not report that the client is current.
- Release EXE and MSI built successfully; WiX validation passed.

The fixture does not use cameras or disturb the installed client. A real
download-and-upgrade between two published versions, final dialog visual review,
and reboot/logon validation are still release acceptance checks. No GitHub
Release is published by the build script.
