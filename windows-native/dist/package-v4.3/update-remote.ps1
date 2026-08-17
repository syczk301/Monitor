param([switch]$DryRun)

$ErrorActionPreference = 'Stop'
$targetBind = '10.95.194.233:8000'
$targetIp = '10.95.194.233'
$sourceExe = Join-Path $PSScriptRoot 'CameraMonitor-V4.3.exe'
$installDirectory = Join-Path $env:LOCALAPPDATA 'Programs\CameraMonitor'
$installedExe = Join-Path $installDirectory 'CameraMonitor.exe'
$settingsPath = Join-Path $env:LOCALAPPDATA 'CameraMonitor\settings.json'

if (-not (Test-Path -LiteralPath $sourceExe)) {
    throw "CameraMonitor-V4.3.exe was not found beside this updater: $sourceExe"
}

Write-Host "Camera Monitor V4.3 remote updater"
Write-Host "Target bind address: $targetBind"

if ($DryRun) {
    Write-Host 'Dry run passed. No process or setting was changed.'
    exit 0
}

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
$isAdministrator = $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdministrator) {
    Write-Host 'Requesting administrator permission for the TCP 8000 firewall rule...'
    $arguments = @(
        '-NoExit',
        '-NoProfile',
        '-ExecutionPolicy', 'Bypass',
        '-File', ('"' + $PSCommandPath + '"')
    )
    $elevated = Start-Process `
        -FilePath 'powershell.exe' `
        -ArgumentList $arguments `
        -Verb RunAs `
        -Wait `
        -PassThru
    exit $elevated.ExitCode
}

Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class CameraMonitorWindow {
    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    public static extern IntPtr FindWindow(string className, string windowName);
    [DllImport("user32.dll")]
    public static extern bool PostMessage(IntPtr window, uint message, UIntPtr wParam, IntPtr lParam);
}
'@

$window = [CameraMonitorWindow]::FindWindow('CameraMonitorRustTrayWindow', $null)
if ($window -ne [IntPtr]::Zero) {
    [void][CameraMonitorWindow]::PostMessage($window, 0x0111, [UIntPtr]1006, [IntPtr]::Zero)
}

$deadline = [DateTime]::UtcNow.AddSeconds(10)
do {
    $running = @(Get-Process -ErrorAction SilentlyContinue | Where-Object {
        $_.ProcessName -like 'CameraMonitor*'
    })
    if ($running.Count -eq 0) { break }
    Start-Sleep -Milliseconds 250
} while ([DateTime]::UtcNow -lt $deadline)

$running = @(Get-Process -ErrorAction SilentlyContinue | Where-Object {
    $_.ProcessName -like 'CameraMonitor*'
})
foreach ($process in $running) {
    Write-Host "Stopping remaining old process $($process.Id)..."
    Stop-Process -Id $process.Id -Force
}

$listeners = @(Get-NetTCPConnection -State Listen -LocalPort 8000 -ErrorAction SilentlyContinue)
foreach ($listener in $listeners) {
    if ($listener.OwningProcess -gt 0) {
        $owner = Get-Process -Id $listener.OwningProcess -ErrorAction SilentlyContinue
        if ($owner) {
            Write-Host "Stopping legacy TCP 8000 process $($owner.ProcessName) ($($owner.Id))..."
            Stop-Process -Id $owner.Id -Force
        }
    }
}

New-Item -ItemType Directory -Path $installDirectory -Force | Out-Null
Copy-Item -LiteralPath $sourceExe -Destination $installedExe -Force

$configuration = Start-Process `
    -FilePath $installedExe `
    -ArgumentList "--set-bind=$targetBind" `
    -WorkingDirectory $installDirectory `
    -WindowStyle Hidden `
    -Wait `
    -PassThru
if ($configuration.ExitCode -ne 0) {
    throw "CameraMonitor.exe could not save the bind address. Exit code: $($configuration.ExitCode)"
}

if (-not (Test-Path -LiteralPath $settingsPath)) {
    throw "Settings file was not created: $settingsPath"
}
$settings = Get-Content -LiteralPath $settingsPath -Raw | ConvertFrom-Json
if ($settings.bind_address -ne $targetBind) {
    throw "Saved bind address is '$($settings.bind_address)', expected '$targetBind'."
}

$runKey = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run'
New-Item -Path $runKey -Force | Out-Null
Set-ItemProperty -Path $runKey -Name 'CameraMonitor' -Value ('"' + $installedExe + '"')
Remove-ItemProperty -Path $runKey -Name 'Camera Monitor' -ErrorAction SilentlyContinue
$legacyChineseStartupName = [string]([char]0x667A) + [char]0x80FD + [char]0x76D1 + [char]0x63A7
Remove-ItemProperty -Path $runKey -Name $legacyChineseStartupName -ErrorAction SilentlyContinue

$firewallRuleName = 'Camera Monitor V4.3 TCP 8000'
Get-NetFirewallRule -DisplayName $firewallRuleName -ErrorAction SilentlyContinue |
    Remove-NetFirewallRule -ErrorAction SilentlyContinue
New-NetFirewallRule `
    -DisplayName $firewallRuleName `
    -Direction Inbound `
    -Action Allow `
    -Protocol TCP `
    -LocalPort 8000 `
    -Profile Any | Out-Null
Write-Host 'Windows Firewall now allows inbound TCP 8000.'

$started = Start-Process -FilePath $installedExe -WorkingDirectory $installDirectory -WindowStyle Hidden -PassThru
Write-Host "Started Camera Monitor V4.3, PID $($started.Id)."

$healthy = $false
for ($attempt = 0; $attempt -lt 20; $attempt++) {
    try {
        $health = Invoke-RestMethod "http://$targetIp`:8000/api/health" -TimeoutSec 1
        if ($health.status -in @('ok', 'degraded')) {
            $healthy = $true
            break
        }
    } catch {
        Start-Sleep -Milliseconds 500
    }
}

if (-not $healthy) {
    throw "V4.3 started and saved $targetBind, but its health endpoint is not reachable. Check whether $targetIp is assigned to this computer and allow inbound TCP 8000 in Windows Firewall."
}

if ([string]::IsNullOrWhiteSpace([string]$health.computer_name)) {
    throw 'V4.3 is reachable, but it did not report the Windows computer name.'
}

Write-Host "Detected Windows computer name: $($health.computer_name)"
Write-Host "Update complete. Dashboard: http://$targetBind"
