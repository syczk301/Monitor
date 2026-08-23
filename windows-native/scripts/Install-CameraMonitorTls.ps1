param(
    [string]$SourceDirectory = (Join-Path $PSScriptRoot '..\dist\tls'),
    [string]$TargetDirectory = (Join-Path $env:LOCALAPPDATA 'CameraMonitor\tls')
)

$ErrorActionPreference = 'Stop'
$source = [System.IO.Path]::GetFullPath($SourceDirectory)
$target = [System.IO.Path]::GetFullPath($TargetDirectory)
$required = @('ca.crt', 'ca.cer', 'server.crt', 'server.key')
foreach ($name in $required) {
    if (-not (Test-Path -LiteralPath (Join-Path $source $name) -PathType Leaf)) {
        throw "Missing TLS file: $(Join-Path $source $name)"
    }
}

[System.IO.Directory]::CreateDirectory($target) | Out-Null
foreach ($name in $required) {
    Copy-Item -LiteralPath (Join-Path $source $name) -Destination (Join-Path $target $name) -Force
}

$importedCa = Import-Certificate `
    -FilePath (Join-Path $target 'ca.cer') `
    -CertStoreLocation 'Cert:\CurrentUser\Root'

Write-Host "Camera Monitor TLS installed for the current Windows user."
Write-Host "Certificate directory: $target"
Write-Host "Trusted CA thumbprint: $($importedCa.Thumbprint)"
