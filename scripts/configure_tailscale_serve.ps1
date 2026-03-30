param(
    [string]$Target = "http://127.0.0.1:8080"
)

function Resolve-TailscalePath {
    $cmd = Get-Command tailscale -ErrorAction SilentlyContinue
    if ($cmd) {
        return $cmd.Source
    }

    $candidates = @(
        "$env:ProgramFiles\Tailscale\tailscale.exe",
        "$env:ProgramFiles(x86)\Tailscale\tailscale.exe",
        "D:\ProgramFiles\Tailscale\tailscale.exe"
    )
    foreach ($candidate in $candidates) {
        if (Test-Path $candidate) {
            return $candidate
        }
    }

    throw "未找到 tailscale。请先安装 Tailscale，并确保 tailscale CLI 可执行文件存在。"
}

$tailscale = Resolve-TailscalePath

& $tailscale status | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "Tailscale 当前未登录或不可用，请先完成登录。"
}

Write-Host "Configuring Tailscale Serve -> $Target"
& $tailscale serve --bg --yes $Target
& $tailscale serve status
