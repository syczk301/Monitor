# Camera Monitor V4.7 ZeroTier HTTPS

V4.7 uses a project-private certificate authority (CA) because public certificate
authorities do not issue certificates for ZeroTier private IP addresses.

## Trust model

- The Windows server presents `server.crt` and keeps `server.key` private.
- The Android APK embeds only `ca.crt` and rejects cleartext HTTP.
- Windows imports the DER-formatted `ca.cer` into the current user's trusted root store so the
  tray dashboard opens without a certificate warning.
- `ca.key` is used only to issue certificates. It stays under the ignored
  `windows-native/dist/tls` directory and must never be copied to clients or Git.

The current certificate covers these IP addresses:

- Main computer: `10.95.194.185`
- Remote computer: `10.95.194.233`
- Android emulator host alias: `10.0.2.2`
- Loopback: `127.0.0.1`

## Generate and install

```powershell
./scripts/New-CameraMonitorTls.ps1
./scripts/Install-CameraMonitorTls.ps1
```

Copy `ca.crt`, `ca.cer`, `server.crt`, and `server.key` to the remote update package and run
`Install-CameraMonitorTls.ps1` there as the remote Windows user. Do not copy
`ca.key`.

After both Windows clients are upgraded, use:

- Main dashboard: `https://10.95.194.185:8000`
- Remote dashboard: `https://10.95.194.233:8000`
- Android server address: `https://10.95.194.185:8000`

Existing remote-node entries such as `10.95.194.233:8000` are migrated to
`https://10.95.194.233:8000` when V4.7 starts.
