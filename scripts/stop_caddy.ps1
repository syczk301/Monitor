$existing = Get-CimInstance Win32_Process |
    Where-Object { $_.Name -eq "caddy.exe" -or $_.CommandLine -like "*caddy run*" }

foreach ($proc in $existing) {
    try {
        Stop-Process -Id $proc.ProcessId -Force -ErrorAction Stop
        Write-Host "Stopped Caddy process $($proc.ProcessId)"
    } catch {
        Write-Warning "无法停止 Caddy 进程: $($proc.ProcessId)"
    }
}
