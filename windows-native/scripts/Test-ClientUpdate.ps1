param([string]$TestRoot = (Join-Path $PSScriptRoot '..\..\.test-runs\client-updater'))
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Client-Update.ps1') -LibraryOnly
$TestRoot = [IO.Path]::GetFullPath((Join-Path $TestRoot ([Guid]::NewGuid().ToString('N'))))
New-Item -ItemType Directory -Path $TestRoot -Force | Out-Null
$script:TestsPassed = 0
function Assert([bool]$Value, [string]$Message) {
    if (-not $Value) { throw "FAIL: $Message" }
    $script:TestsPassed++
}
function Assert-Throws([scriptblock]$Action, [string]$Message) {
    $thrown = $false
    try { & $Action | Out-Null } catch { $thrown = $true }
    Assert $thrown $Message
}
function New-FixtureRelease {
    return [pscustomobject]@{
        tag_name = 'v4.8.0'; draft = $false; prerelease = $false; body = 'Update notes'
        assets = @(
            [pscustomobject]@{name='CameraMonitor.exe'; size=8; browser_download_url='https://github.com/syczk301/Monitor/releases/download/v4.8.0/CameraMonitor.exe'},
            [pscustomobject]@{name='CameraMonitor.exe.sha256'; size=84; browser_download_url='https://github.com/syczk301/Monitor/releases/download/v4.8.0/CameraMonitor.exe.sha256'}
        )
    }
}

$release = New-FixtureRelease
Assert ((Get-UpdateCandidate $release '4.7.1').Version -eq '4.8.0') 'new stable release'
Assert ($null -eq (Get-UpdateCandidate $release '4.8.0')) 'equal version'
Assert ($null -eq (Get-UpdateCandidate $release '4.10.0')) 'numeric version order; no downgrade'
Assert ($null -eq (Get-UpdateCandidate $null '4.8.0')) 'no release'
$release.prerelease = $true
Assert ($null -eq (Get-UpdateCandidate $release '4.7.1')) 'skip prereleases'
$release = New-FixtureRelease; $release.draft = $true
Assert ($null -eq (Get-UpdateCandidate $release '4.7.1')) 'skip drafts'
Assert-Throws { ConvertTo-ReleaseVersion 'v4.8.0;evil' } 'reject executable version text'
Assert-Throws { ConvertTo-ReleaseVersion '4.8.0-beta' } 'reject beta versions'
$release = New-FixtureRelease; $release.assets[0].browser_download_url = 'http://github.com/syczk301/Monitor/releases/download/v4.8.0/CameraMonitor.exe'
Assert-Throws { Get-UpdateCandidate $release '4.7.1' } 'reject HTTP'
$release = New-FixtureRelease; $release.assets[0].browser_download_url = 'https://evil.example/CameraMonitor.exe'
Assert-Throws { Get-UpdateCandidate $release '4.7.1' } 'reject different repository/host'
$release = New-FixtureRelease; $release.assets[0].size = 100MB
Assert-Throws { Get-UpdateCandidate $release '4.7.1' } 'reject oversized download'
$release = New-FixtureRelease; $release.assets = @($release.assets[0])
Assert-Throws { Get-UpdateCandidate $release '4.7.1' } 'require checksum'
$release = New-FixtureRelease; $release.assets += $release.assets[0]
Assert-Throws { Get-UpdateCandidate $release '4.7.1' } 'reject duplicate assets'

$payload = Join-Path $TestRoot 'payload.exe'
$checksum = Join-Path $TestRoot 'payload.sha256'
[IO.File]::WriteAllBytes($payload, [byte[]](77,90,1,2,3,4,5,6))
$hash = (Get-FileHash -LiteralPath $payload -Algorithm SHA256).Hash
[IO.File]::WriteAllText($checksum, "$hash  CameraMonitor.exe")
Test-UpdatePayload $payload $checksum
Assert $true 'correct checksum'
[IO.File]::AppendAllText($payload, 'corrupt')
Assert-Throws { Test-UpdatePayload $payload $checksum } 'reject corrupt download before stopping client'
[IO.File]::WriteAllText($checksum, 'not-a-hash')
Assert-Throws { Test-UpdatePayload $payload $checksum } 'reject malformed checksum'
[IO.File]::WriteAllText($payload, 'HTML instead of executable')
$hash = (Get-FileHash -LiteralPath $payload -Algorithm SHA256).Hash
[IO.File]::WriteAllText($checksum, $hash)
Assert-Throws { Test-UpdatePayload $payload $checksum } 'reject non-PE payload'

$target = Join-Path $TestRoot 'CameraMonitor.exe'
$staged = Join-Path $TestRoot 'CameraMonitor.new'
$backup = Join-Path $TestRoot 'CameraMonitor.backup.exe'
$settings = Join-Path $TestRoot 'settings.json'
[IO.File]::WriteAllText($settings, 'settings-and-certificates-must-stay')
[IO.File]::WriteAllText($target, 'old')
[IO.File]::WriteAllText($staged, 'new')
$script:Started = @()
$script:Stopped = @()
$start = { param($path) $script:Started += [IO.File]::ReadAllText($path); return 'process' }
$stop = { param($process) $script:Stopped += $process }
Invoke-ExecutableReplacement $target $staged $backup $start { return $true } $stop
Assert ([IO.File]::ReadAllText($target) -eq 'new') 'replace executable'
Assert ([IO.File]::ReadAllText($backup) -eq 'old') 'keep backup'
Assert ($script:Started.Count -eq 1) 'start new client once'
Assert ([IO.File]::ReadAllText($settings) -eq 'settings-and-certificates-must-stay') 'preserve configuration'

$backup2 = Join-Path $TestRoot 'rollback.backup.exe'
[IO.File]::WriteAllText($staged, 'broken')
$script:Started = @(); $script:Stopped = @()
Assert-Throws { Invoke-ExecutableReplacement $target $staged $backup2 $start { return $false } $stop } 'report failed startup'
Assert ([IO.File]::ReadAllText($target) -eq 'new') 'restore original executable after failure'
Assert (($script:Started -join ',') -eq 'broken,new') 'restart original client after rollback'
Assert ($script:Stopped.Count -eq 1) 'stop only replacement process'
[IO.File]::WriteAllText($staged, 'next')
Assert-Throws { Invoke-ExecutableReplacement $target $staged $backup $start { $true } $stop } 'never overwrite an existing backup'
Assert-Throws { Invoke-ExecutableReplacement $target $staged (Join-Path $TestRoot '..\outside.exe') $start { $true } $stop } 'reject backup outside target directory'
Assert-Throws { Invoke-ExecutableReplacement $target $target $backup2 $start { $true } $stop } 'reject replacing from same path'
Assert ([IO.File]::ReadAllText($target) -eq 'new') 'invalid paths leave target untouched'
Write-Host "PASS: $script:TestsPassed updater checks. Fixtures: $TestRoot"
