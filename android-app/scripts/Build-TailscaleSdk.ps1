param(
    [Parameter(Mandatory=$true)][string]$NdkDirectory,
    [string]$GoExecutable = 'go',
    [string]$AndroidSdk = "$env:LOCALAPPDATA\Android\Sdk"
)
$ErrorActionPreference = 'Stop'
$androidRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$workspace = [IO.Path]::GetFullPath((Join-Path $androidRoot '..'))
$toolsRoot = Join-Path $workspace '.test-runs\tailscale-tools'
$env:GOPATH = Join-Path $toolsRoot 'gopath'
$env:GOCACHE = Join-Path $toolsRoot 'gocache'
$env:GOTELEMETRY = 'off'
$goCommand = (Get-Command $GoExecutable -ErrorAction Stop).Source
$env:PATH = "$(Split-Path $goCommand);$env:GOPATH\bin;$env:PATH"
$env:ANDROID_HOME = $AndroidSdk
$env:ANDROID_NDK_HOME = [IO.Path]::GetFullPath($NdkDirectory)
if (-not (Test-Path -LiteralPath (Join-Path $env:ANDROID_NDK_HOME 'source.properties'))) { throw 'NDK directory missing' }
$mobileVersion = 'v0.0.0-20261011034528-197770444867'
& $goCommand install "golang.org/x/mobile/cmd/gobind@$mobileVersion"
if ($LASTEXITCODE -ne 0) { throw 'gobind build failed' }
Push-Location (Join-Path $androidRoot 'tailscale')
try {
    & $goCommand mod download
    if ($LASTEXITCODE -ne 0) { throw 'Tailscale dependencies failed' }
    & $goCommand test ./...
    if ($LASTEXITCODE -ne 0) { throw 'Tailscale bridge tests failed' }
    # Use the official gobind generator and Go/NDK toolchain directly.
    $bindingRoot = Join-Path $workspace '.test-runs\tailscale-bind'
    $staging = Join-Path $bindingRoot 'aar'
    New-Item -ItemType Directory -Path $bindingRoot,$staging -Force | Out-Null
    $env:GOOS = 'android'
    $env:GOARCH = 'arm64'
    $env:CGO_ENABLED = '1'
    & (Join-Path $env:GOPATH 'bin\gobind.exe') '-lang=go,java' "-outdir=$bindingRoot" .
    if ($LASTEXITCODE -ne 0) { throw 'Tailscale JNI binding generation failed' }
    $nativeSource = Join-Path $bindingRoot 'src'
    $originalSource = (Get-Location).Path.Replace('\','/')
    $bindingModule = (Get-Content go.mod -Raw).Replace('module monitor.local/tailscale', 'module monitor.local/tailscale-bind')
    $bindingModule += "`nrequire monitor.local/tailscale v0.0.0`nreplace monitor.local/tailscale => $originalSource`n"
    [IO.File]::WriteAllText((Join-Path $nativeSource 'go.mod'), $bindingModule)
    Copy-Item -LiteralPath go.sum -Destination (Join-Path $nativeSource 'go.sum') -Force
    $clang = Join-Path $env:ANDROID_NDK_HOME 'toolchains\llvm\prebuilt\windows-x86_64\bin\clang.exe'
    $targets = @(
        @{ Arch = 'amd64'; Abi = 'x86_64'; Triple = 'x86_64-linux-android26' },
        @{ Arch = 'arm64'; Abi = 'arm64-v8a'; Triple = 'aarch64-linux-android26' },
        @{ Arch = 'arm'; Abi = 'armeabi-v7a'; Triple = 'armv7a-linux-androideabi26' }
    )
    Push-Location $nativeSource
    try {
        foreach ($target in $targets) {
            $env:GOARCH = $target.Arch
            $env:GOARM = '7'
            $env:CC = '"' + $clang + '" --target=' + $target.Triple
            $destination = Join-Path $staging "jni\$($target.Abi)"
            New-Item -ItemType Directory -Path $destination -Force | Out-Null
            & $goCommand build -mod=mod -trimpath -buildmode=c-shared -ldflags '-s -w -extldflags=-Wl,-z,max-page-size=16384' -o (Join-Path $destination 'libgojni.so') ./gobind
            if ($LASTEXITCODE -ne 0) { throw "Tailscale native build failed: $($target.Abi)" }
            Remove-Item -LiteralPath (Join-Path $destination 'libgojni.h') -ErrorAction SilentlyContinue
            Write-Output "Built Tailscale $($target.Abi)"
        }
    } finally { Pop-Location }
    Remove-Item Env:GOOS,Env:GOARCH,Env:GOARM,Env:CC,Env:CGO_ENABLED -ErrorAction SilentlyContinue
    $classes = Join-Path $bindingRoot 'classes'
    New-Item -ItemType Directory -Path $classes -Force | Out-Null
    $javaFiles = Get-ChildItem -LiteralPath (Join-Path $bindingRoot 'java') -Recurse -Filter '*.java' | Select-Object -ExpandProperty FullName
    & javac -source 8 -target 8 -bootclasspath (Join-Path $AndroidSdk 'platforms\android-35\android.jar') -d $classes @javaFiles
    if ($LASTEXITCODE -ne 0) { throw 'Tailscale Java compilation failed' }
    & jar cf (Join-Path $staging 'classes.jar') -C $classes .
    if ($LASTEXITCODE -ne 0) { throw 'Tailscale Java packaging failed' }
    [IO.File]::WriteAllText((Join-Path $staging 'AndroidManifest.xml'), '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="go.tsbridge.gojni"><uses-sdk android:minSdkVersion="26" /></manifest>')
    [IO.File]::WriteAllText((Join-Path $staging 'proguard.txt'), "-keep class go.** { *; }`n-keep class tsbridge.** { *; }`n")
    [IO.File]::WriteAllText((Join-Path $staging 'R.txt'), '')
    New-Item -ItemType Directory -Path (Join-Path $staging 'res') -Force | Out-Null
    & jar cf (Join-Path $androidRoot 'app\libs\tailscale.aar') -C $staging AndroidManifest.xml -C $staging classes.jar -C $staging jni -C $staging proguard.txt -C $staging R.txt -C $staging res
    if ($LASTEXITCODE -ne 0) { throw 'Tailscale AAR packaging failed' }
    # Ship licenses for the Go modules linked into the Android library.
    $noticeDirectory = Join-Path $androidRoot 'app\src\main\assets\third-party\tailscale'
    New-Item -ItemType Directory -Path $noticeDirectory -Force | Out-Null
    $goRoot = & $goCommand env GOROOT
    Copy-Item -LiteralPath (Join-Path $goRoot 'LICENSE') -Destination (Join-Path $noticeDirectory 'Go-LICENSE') -Force
    $env:GOOS = 'android'
    $env:GOARCH = 'arm64'
    $modulePaths = & $goCommand list -deps -f '{{with .Module}}{{.Path}}{{end}}' . | Sort-Object -Unique
    if ($LASTEXITCODE -ne 0) { throw 'License dependency enumeration failed' }
    Remove-Item Env:GOOS,Env:GOARCH
    foreach ($module in $modulePaths) {
        if (-not $module -or $module -eq 'monitor.local/tailscale') { continue }
        $metadata = & $goCommand list -m -json $module | ConvertFrom-Json
        $licenseFiles = Get-ChildItem -LiteralPath $metadata.Dir -File | Where-Object { $_.Name -match '^(LICENSE|COPYING|NOTICE|PATENTS)' -and $_.Extension -ne '.go' }
        foreach ($license in $licenseFiles) {
            $name = ($module -replace '[^a-zA-Z0-9.-]', '_') + '-' + $license.Name
            Copy-Item -LiteralPath $license.FullName -Destination (Join-Path $noticeDirectory $name) -Force
        }
    }
    $aarFile = Join-Path $androidRoot 'app\libs\tailscale.aar'
    $sourceHashes = [ordered]@{}
    foreach ($sourceFile in @('bridge.go','route_android.go','route_other.go','go.mod','go.sum')) {
        $sourceHashes[$sourceFile] = (Get-FileHash -LiteralPath $sourceFile -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    $ndkRevision = ((Get-Content -LiteralPath (Join-Path $env:ANDROID_NDK_HOME 'source.properties') |
        Where-Object { $_ -match '^Pkg.Revision\s*=' }) -split '=', 2)[1].Trim()
    $provenance = [ordered]@{
        aar = 'tailscale.aar'
        sha256 = (Get-FileHash -LiteralPath $aarFile -Algorithm SHA256).Hash.ToLowerInvariant()
        go = (& $goCommand version)
        gomobile = $mobileVersion
        androidNdk = $ndkRevision
        androidApi = 26
        abis = @($targets | ForEach-Object { $_.Abi })
        sourceSha256 = $sourceHashes
    }
    [IO.File]::WriteAllText((Join-Path $androidRoot 'app\libs\tailscale-build.json'), ($provenance | ConvertTo-Json -Depth 4) + "`n")
    Get-FileHash -LiteralPath $aarFile -Algorithm SHA256
} finally {
    Remove-Item Env:GOOS,Env:GOARCH,Env:GOARM,Env:CC,Env:CGO_ENABLED -ErrorAction SilentlyContinue
    Pop-Location
}
