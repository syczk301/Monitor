param(
    [Parameter(Mandatory = $true)]
    [string]$Password
)

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
& $caddy hash-password --plaintext $Password
