param([switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $SkipBuild) {
    $temporary = [IO.Path]::GetFullPath((Join-Path $root '..\.test-runs\java-build'))
    New-Item -ItemType Directory -Path $temporary -Force | Out-Null
    $previousJavaOptions = $env:JAVA_TOOL_OPTIONS
    Push-Location $root
    try {
        $env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=$temporary -Djava.io.tmpdir=$temporary"
        & .\gradlew.bat --no-daemon testDebugUnitTest assembleRelease
        if ($LASTEXITCODE -ne 0) { throw 'Android release build failed' }
    } finally { Pop-Location; $env:JAVA_TOOL_OPTIONS = $previousJavaOptions }
}
$manifest = Get-Content -LiteralPath (Join-Path $root 'app\build.gradle.kts') -Raw
if ($manifest -notmatch 'versionName\s*=\s*"([vV]?\d+\.\d+\.\d+)"') { throw 'Android version missing' }
$version = $Matches[1]
$metadata = Get-Content -LiteralPath (Join-Path $root 'app\build\outputs\apk\release\output-metadata.json') -Raw | ConvertFrom-Json
if ($metadata.elements.Count -ne 1 -or $metadata.elements[0].versionName -ne $version -or $metadata.applicationId -ne 'com.monitor.intelligentflow') {
    throw 'APK metadata does not match the release version; rebuild before packaging'
}
$directory = Join-Path $root 'dist'
New-Item -ItemType Directory -Path $directory -Force | Out-Null
$apk = Join-Path $directory "monitor-$version-release.apk"
Copy-Item -LiteralPath (Join-Path $root 'app\build\outputs\apk\release\app-release.apk') -Destination $apk -Force
$hash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText("$apk.sha256", "$hash  $([IO.Path]::GetFileName($apk))`n", [Text.Encoding]::ASCII)
Get-Item -LiteralPath $apk,"$apk.sha256" | Select-Object Name,Length
