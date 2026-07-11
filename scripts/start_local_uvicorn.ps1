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
$logDirectory = Join-Path $repoRoot "data"
New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
$stdoutLog = Join-Path $logDirectory "uvicorn.out.log"
$stderrLog = Join-Path $logDirectory "uvicorn.err.log"

$process = Start-Process python -ArgumentList @(
    "-m", "uvicorn", "app.main:app",
    "--host", $HostAddress,
    "--port", "$Port"
) -WorkingDirectory $repoRoot `
  -WindowStyle Hidden `
  -RedirectStandardOutput $stdoutLog `
  -RedirectStandardError $stderrLog `
  -PassThru

Set-Content -Path (Join-Path $logDirectory "uvicorn.pid") -Value $process.Id
