# Client updates (4.8)

Right-click the Windows tray icon and select **检查更新…**. The updater checks
`https://api.github.com/repos/syczk301/Monitor/releases/latest`. The user sees the
new version and release notes before approving a download and restart. If no
stable release is published, the dialog says so; network failures are errors,
not a claim that the installed version is current.

The Windows PowerShell helper is embedded in the EXE and runs independently so
the client can finish its current recording and exit. It downloads only the
expected asset URLs from this repository's tagged GitHub release, validates the
size and SHA-256 checksum, then stages the EXE next to the installed executable.
No unauthenticated web update endpoint is added.

The updater requests a normal tray shutdown, waits up to 60 seconds, and never
force-kills an active recording. The original EXE is retained with a unique
backup filename. If the new client exits or fails its HTTPS/version readiness
check, the helper stops the replacement, restores the original EXE and restarts
it. If a replacement cannot stop safely, the backup is retained for manual
recovery. Settings, certificates, recordings and Windows startup preferences
are not rewritten; the executable stays at the same path. An unwritable install
directory is detected before the client is stopped. Updates use the current
user's permissions and do not request automatic elevation.

The readiness check uses the existing trusted local HTTPS certificate. Update
logs are under `%LOCALAPPDATA%\CameraMonitor\logs\update.log`. Backups and staged
downloads are retained for recovery. The application does not silently install
updates, downgrade versions, or clear a user's Task Manager startup-disable flag.

## Publishing

1. Set the workspace version in `Cargo.toml` and update `Cargo.lock`.
2. Run `cargo test --workspace --locked` and `scripts/Test-ClientUpdate.ps1`.
3. Run `scripts/Build-Release.ps1`.
4. Publish a **stable** GitHub Release tagged `vX.Y.Z` containing exactly:
   - `CameraMonitor.exe`
   - `CameraMonitor.exe.sha256`

The GitHub repository and HTTPS protect the release metadata; the checksum
detects corrupt downloads. This is not an Authenticode signing implementation.
Do not publish device settings, TLS private keys, or a populated runtime folder.

Existing 4.7 clients need one manual upgrade to 4.8 before they have this menu.
Publishing an asset does not upgrade those older clients automatically. New
computers still require HTTPS/camera setup; this is an updater for configured
clients, not an initial provisioning tool.

For a noninteractive read-only check:

```powershell
powershell -NoProfile -File scripts/Client-Update.ps1 -CheckOnly -CurrentVersion 4.8.0
```

For tests without touching the installed client or registry:

```powershell
powershell -NoProfile -File scripts/Test-ClientUpdate.ps1
```

The integration test launches hidden fixture processes and exercises real Win32
shutdown, file replacement and rollback without using cameras or touching the
installed client:

```powershell
cargo build -p monitor-app --example updater_fixture
powershell -NoProfile -File scripts/Test-ClientUpdateIntegration.ps1 -FixtureExe target/debug/examples/updater_fixture.exe
```
