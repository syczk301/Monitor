param(
    [string]$HostAddress = "127.0.0.1",
    [int]$Port = 8000
)

$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

$existing = Get-CimInstance Win32_Process |
    Where-Object { $_.Name -eq "python.exe" -and $_.CommandLine -like "*uvicorn app.main:app*" }

foreach ($proc in $existing) {
    try {
        Stop-Process -Id $proc.ProcessId -Force -ErrorAction Stop
    } catch {
        Write-Warning "无法停止旧的 uvicorn 进程: $($proc.ProcessId)"
    }
}

Write-Host "Starting uvicorn on http://$HostAddress`:$Port ..."
Start-Process python -ArgumentList @(
    "-m", "uvicorn", "app.main:app",
    "--host", $HostAddress,
    "--port", "$Port"
) -WorkingDirectory $repoRoot
