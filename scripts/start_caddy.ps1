$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

function Import-EnvFile {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    if (-not (Test-Path $Path)) {
        return
    }

    Get-Content $Path | ForEach-Object {
        $line = $_.Trim()
        if (-not $line -or $line.StartsWith("#")) {
            return
        }

        $parts = $line -split "=", 2
        if ($parts.Count -ne 2) {
            return
        }

        $name = $parts[0].Trim()
        $value = $parts[1].Trim().Trim('"')
        if ($name) {
            [Environment]::SetEnvironmentVariable($name, $value, "Process")
        }
    }
}

function Resolve-CaddyPath {
    $cmd = Get-Command caddy -ErrorAction SilentlyContinue
    if ($cmd) {
        return $cmd.Source
    }

    $candidates = @(
        "$env:LOCALAPPDATA\Microsoft\WinGet\Packages\CaddyServer.Caddy_Microsoft.Winget.Source_8wekyb3d8bbwe\caddy.exe",
        "$env:ProgramFiles\Caddy\caddy.exe"
    )
    foreach ($candidate in $candidates) {
        if (Test-Path $candidate) {
            return $candidate
        }
    }

    $wingetDir = Get-ChildItem "$env:LOCALAPPDATA\Microsoft\WinGet\Packages" -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like "CaddyServer.Caddy*" } |
        Select-Object -First 1
    if ($wingetDir) {
        $candidate = Join-Path $wingetDir.FullName "caddy.exe"
        if (Test-Path $candidate) {
            return $candidate
        }
    }

    throw "未找到 caddy。请先安装 Caddy，并确保 caddy 可执行文件存在。"
}

$caddy = Resolve-CaddyPath
$envFile = Join-Path $repoRoot "Caddy.local.env"
Import-EnvFile -Path $envFile
$user = $env:CADDY_BASIC_AUTH_USER
$hash = $env:CADDY_BASIC_AUTH_HASH

if ([string]::IsNullOrWhiteSpace($user) -or [string]::IsNullOrWhiteSpace($hash)) {
    throw "缺少 CADDY_BASIC_AUTH_USER 或 CADDY_BASIC_AUTH_HASH 环境变量。"
}

$existing = Get-Process -Name "caddy" -ErrorAction SilentlyContinue

foreach ($proc in $existing) {
    try {
        Stop-Process -Id $proc.Id -Force -ErrorAction Stop
    } catch {
        Write-Warning "无法停止旧的 Caddy 进程: $($proc.Id)"
    }
}

Write-Host "Starting Caddy on http://127.0.0.1:8080 ..."
Start-Process $caddy -ArgumentList @("run", "--config", (Join-Path $repoRoot "Caddyfile")) -WorkingDirectory $repoRoot
