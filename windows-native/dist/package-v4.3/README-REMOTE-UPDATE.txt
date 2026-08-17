Camera Monitor V4.3 Windows remote update package

Target remote address: 10.95.194.233:8000

Update steps:
1. Extract every file in this ZIP on the remote computer.
2. Double-click RUN-THIS-FIRST.cmd. Accept the Windows administrator prompt. Do not start the EXE directly for the first update.
3. The updater closes old Rust/C# tray processes and any legacy process listening on TCP 8000.
4. It installs V4.3 under LocalAppData, replaces legacy startup entries, writes 10.95.194.233:8000, verifies the saved setting, and starts V4.3.
5. It creates an inbound Windows Firewall rule for TCP 8000 on the new 10.95 network.
6. Refresh the camera list on the main computer. The remote camera should appear online.

If TCP port 8000 is still unreachable, keep the updater window open and send its complete output for diagnosis.
