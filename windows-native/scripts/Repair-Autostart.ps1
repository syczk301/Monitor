# Run on the affected computer, while CameraMonitor is running.
$ErrorActionPreference = 'Stop'
$runKey = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run'
$existing = (Get-ItemProperty -LiteralPath $runKey -ErrorAction SilentlyContinue).CameraMonitor
if ([string]::IsNullOrWhiteSpace($existing)) {
    Write-Host 'No existing CameraMonitor startup registration. No change made.'
    return
}
$session = (Get-Process -Id $PID).SessionId
$paths = @(Get-Process -Name CameraMonitor -ErrorAction SilentlyContinue |
    Where-Object { $_.SessionId -eq $session } |
    ForEach-Object { $_.Path } |
    Where-Object { $_ -and (Test-Path -LiteralPath $_ -PathType Leaf) } |
    Sort-Object -Unique)
if ($paths.Count -ne 1) {
    throw 'Keep exactly one CameraMonitor version running in this session, then retry.'
}
$arguments = ''
if ($existing -match '^\s*"[^"]+"(.*)$') { $arguments = $Matches[1] }
elseif ($existing -match '(?i)^\s*.*?\.exe(.*)$') { $arguments = $Matches[1] }
else { throw 'Unrecognized startup command. No change made.' }
$command = '"' + $paths[0] + '"' + $arguments
Set-ItemProperty -LiteralPath $runKey -Name CameraMonitor -Value $command
if ((Get-ItemPropertyValue -LiteralPath $runKey -Name CameraMonitor) -cne $command) {
    throw 'Startup registration verification failed.'
}
Write-Host "Computer: $env:COMPUTERNAME"
Write-Host "Before: $existing"
Write-Host "After:  $command"
Write-Host 'Reopen the tray menu to refresh its status. Windows startup approval was not changed.'
