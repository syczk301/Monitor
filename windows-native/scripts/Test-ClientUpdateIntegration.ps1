param([Parameter(Mandatory=$true)][string]$FixtureExe)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Client-Update.ps1') -LibraryOnly
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot ('..\..\.test-runs\update-process-' + [Guid]::NewGuid().ToString('N'))))
New-Item -ItemType Directory -Path $root | Out-Null
$target = Join-Path $root 'CameraMonitor.exe'
$staged = Join-Path $root 'CameraMonitor.new'
$backup = Join-Path $root 'CameraMonitor.backup.exe'
Copy-Item -LiteralPath $FixtureExe -Destination $target
Copy-Item -LiteralPath $FixtureExe -Destination $staged
$originalHash = (Get-FileHash -LiteralPath $target).Hash
$configuration = Join-Path $root 'settings.json'
[IO.File]::WriteAllText($configuration, 'preserve-original-configuration')

function Start-Fixture([string]$Path) {
    $process = Start-Process -FilePath $Path -WindowStyle Hidden -PassThru
    $marker = [IO.Path]::ChangeExtension($Path, "ready.$($process.Id)")
    $deadline = [DateTime]::UtcNow.AddSeconds(10)
    while (-not (Test-Path -LiteralPath $marker) -and [DateTime]::UtcNow -lt $deadline) { Start-Sleep -Milliseconds 50 }
    if (-not (Test-Path -LiteralPath $marker)) { throw 'Fixture did not create its hidden window' }
    $script:LastFixtureProcess = $process
    return $process
}
function Stop-Fixture($Process) {
    $Process.Refresh()
    if (-not $Process.HasExited) {
        Stop-ClientGracefully $Process.Id $target 'CameraMonitorUpdaterFixture'
    }
}
$script:LastFixtureProcess = $null
try {
    $old = Start-Fixture $target
    Stop-Fixture $old
    Invoke-ExecutableReplacement $target $staged $backup { param($p) Start-Fixture $p } { param($p) return -not $p.HasExited } { param($p) Stop-Fixture $p }
    if (-not (Test-Path -LiteralPath $backup)) { throw 'Backup missing' }
    Stop-Fixture $script:LastFixtureProcess

    Copy-Item -LiteralPath $FixtureExe -Destination $staged
    $backup2 = Join-Path $root 'rollback.backup.exe'
    $failed = $false
    try {
        Invoke-ExecutableReplacement $target $staged $backup2 { param($p) Start-Fixture $p } { return $false } { param($p) Stop-Fixture $p }
    } catch {
        if ($_ -notlike '*已恢复旧版*') { throw }
        $failed = $true
    }
    if (-not $failed) { throw 'Expected simulated readiness failure' }
    if ((Get-FileHash -LiteralPath $target).Hash -ne $originalHash) { throw 'Rollback hash mismatch' }
    if ([IO.File]::ReadAllText($configuration) -ne 'preserve-original-configuration') { throw 'Settings changed' }
    $script:LastFixtureProcess.Refresh()
    if ($script:LastFixtureProcess.HasExited) { throw 'Restored process is not running' }
    Write-Host 'PASS: real hidden-process shutdown, executable replacement, restart, readiness failure rollback, configuration preservation.'
} finally {
    if ($script:LastFixtureProcess) { Stop-Fixture $script:LastFixtureProcess }
}
