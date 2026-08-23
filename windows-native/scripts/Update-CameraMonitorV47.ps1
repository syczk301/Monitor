param(
    [string]$TargetBind = '10.95.194.233:8000',
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$targetParts = $TargetBind.Split(':')
if ($targetParts.Count -ne 2) {
    throw "TargetBind must use IPv4:port format: $TargetBind"
}
$targetIp = [System.Net.IPAddress]::Parse($targetParts[0]).ToString()
$targetPort = [int]$targetParts[1]
if ($targetPort -lt 1 -or $targetPort -gt 65535) {
    throw "Target port is invalid: $targetPort"
}
$sourceExe = Join-Path $PSScriptRoot 'CameraMonitor-V4.7.0.exe'
$sourceTls = Join-Path $PSScriptRoot 'tls'
$installDirectory = Join-Path $env:LOCALAPPDATA 'Programs\CameraMonitor'
$installedExe = Join-Path $installDirectory 'CameraMonitor.exe'
$settingsPath = Join-Path $env:LOCALAPPDATA 'CameraMonitor\settings.json'
$targetTls = Join-Path $env:LOCALAPPDATA 'CameraMonitor\tls'

if (-not (Test-Path -LiteralPath $sourceExe -PathType Leaf)) {
    throw "CameraMonitor-V4.7.0.exe was not found beside this updater: $sourceExe"
}
foreach ($name in @('ca.crt', 'ca.cer', 'server.crt', 'server.key')) {
    if (-not (Test-Path -LiteralPath (Join-Path $sourceTls $name) -PathType Leaf)) {
        throw "TLS file was not found: $(Join-Path $sourceTls $name)"
    }
}

Write-Host 'Camera Monitor V4.7.0 ZeroTier HTTPS updater'
Write-Host "Target HTTPS bind address: $TargetBind"
if ($DryRun) {
    Write-Host 'Dry run passed. No process, certificate, firewall rule, or setting was changed.'
    exit 0
}

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
$isAdministrator = $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdministrator) {
    Write-Host 'Requesting administrator permission for the TCP 8000 firewall rule...'
    $arguments = @(
        '-NoProfile',
        '-ExecutionPolicy', 'Bypass',
        '-File', ('"' + $PSCommandPath + '"'),
        '-TargetBind', ('"' + $TargetBind + '"')
    )
    $elevated = Start-Process -FilePath 'powershell.exe' -ArgumentList $arguments -Verb RunAs -Wait -PassThru
    exit $elevated.ExitCode
}

Get-Process -ErrorAction SilentlyContinue |
    Where-Object ProcessName -Like 'CameraMonitor*' |
    Stop-Process -Force

New-Item -ItemType Directory -Path $installDirectory -Force | Out-Null
New-Item -ItemType Directory -Path $targetTls -Force | Out-Null
Copy-Item -LiteralPath $sourceExe -Destination $installedExe -Force
foreach ($name in @('ca.crt', 'ca.cer', 'server.crt', 'server.key')) {
    Copy-Item -LiteralPath (Join-Path $sourceTls $name) -Destination (Join-Path $targetTls $name) -Force
}

Import-Certificate `
    -FilePath (Join-Path $targetTls 'ca.cer') `
    -CertStoreLocation 'Cert:\CurrentUser\Root' | Out-Null

$configuration = Start-Process -FilePath $installedExe `
    -ArgumentList "--set-bind=$TargetBind" `
    -WorkingDirectory $installDirectory `
    -WindowStyle Hidden `
    -Wait `
    -PassThru
if ($configuration.ExitCode -ne 0) {
    throw "CameraMonitor.exe could not save the bind address. Exit code: $($configuration.ExitCode)"
}

$settings = Get-Content -LiteralPath $settingsPath -Raw | ConvertFrom-Json
if ($settings.bind_address -ne $TargetBind) {
    throw "Saved bind address is '$($settings.bind_address)', expected '$TargetBind'."
}

$runKey = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run'
New-Item -Path $runKey -Force | Out-Null
Set-ItemProperty -Path $runKey -Name 'CameraMonitor' -Value ('"' + $installedExe + '"')

Get-NetFirewallRule -DisplayName 'Camera Monitor HTTPS TCP 8000' -ErrorAction SilentlyContinue |
    Remove-NetFirewallRule -ErrorAction SilentlyContinue
New-NetFirewallRule -DisplayName 'Camera Monitor HTTPS TCP 8000' `
    -Direction Inbound -Action Allow -Protocol TCP -LocalPort 8000 -Profile Any | Out-Null

$started = Start-Process -FilePath $installedExe -WorkingDirectory $installDirectory -WindowStyle Hidden -PassThru
Write-Host "Started Camera Monitor V4.7.0, PID $($started.Id)."

$health = $null
for ($attempt = 0; $attempt -lt 30; $attempt++) {
    try {
        $health = Invoke-RestMethod "https://$TargetBind/api/health" -TimeoutSec 2
        if ($health.protocol -eq 'https' -and $health.version -eq '4.7.0') { break }
    } catch {
        Start-Sleep -Milliseconds 500
    }
}
if ($null -eq $health -or $health.protocol -ne 'https' -or $health.version -ne '4.7.0') {
    throw "V4.7.0 started, but HTTPS health verification failed at https://$TargetBind/api/health."
}

Write-Host "Detected computer: $($health.computer_name)"
Write-Host "HTTPS verification passed: https://$TargetBind"
