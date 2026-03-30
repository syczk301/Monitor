Write-Host "Installing Caddy ..."
winget install --id CaddyServer.Caddy --accept-package-agreements --accept-source-agreements

Write-Host "Installing Tailscale ..."
winget install --id Tailscale.Tailscale --accept-package-agreements --accept-source-agreements

Write-Host "Done. Re-open PowerShell if commands are not yet on PATH."
