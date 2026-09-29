param([switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
Push-Location $root
try {
    if (-not $SkipBuild) {
        & cargo build --release --locked -p monitor-app
        if ($LASTEXITCODE -ne 0) { throw 'Release build failed' }
    }
    $manifest = Get-Content -LiteralPath (Join-Path $root 'Cargo.toml') -Raw
    if ($manifest -notmatch '(?m)^version = "(\d+\.\d+\.\d+)"') { throw 'Workspace version missing' }
    $version = $Matches[1]
    $source = Join-Path $root 'target\release\CameraMonitor.exe'
    $binaryVersion = (& $source --version | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or $binaryVersion -ne $version) {
        throw "Binary version '$binaryVersion' does not match manifest '$version'; rebuild before packaging"
    }
    $directory = Join-Path $root "dist\release-v$version"
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    $exe = Join-Path $directory 'CameraMonitor.exe'
    Copy-Item -LiteralPath $source -Destination $exe -Force
    $hash = (Get-FileHash -LiteralPath $exe -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText((Join-Path $directory 'CameraMonitor.exe.sha256'), "$hash  CameraMonitor.exe`n", [Text.Encoding]::ASCII)
    Write-Host "Upload these two assets to GitHub Release v$version (stable, not prerelease):"
    Get-ChildItem -LiteralPath $directory | Select-Object Name,Length
} finally { Pop-Location }
