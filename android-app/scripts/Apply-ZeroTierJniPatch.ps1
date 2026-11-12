param([Parameter(Mandatory=$true)][string]$SourceDirectory)
$ErrorActionPreference = 'Stop'
$source = [IO.Path]::GetFullPath($SourceDirectory)
$file = Join-Path $source 'src\bindings\java\JavaSockets.cxx'
$patch = Join-Path $PSScriptRoot 'patches\libzt-jni-safe-buffers.patch'
$pinned = 'a707ea6ae0910efdc1125d04758c411e2e9ea4f9'
if ((& git -C $source rev-parse HEAD).Trim() -ne $pinned) { throw 'Unexpected SDK source revision' }
# Normalize checkout line endings before comparing the pinned source content.
function Get-SourceHash([string]$Path) {
    $text = [IO.File]::ReadAllText($Path).Replace("`r`n", "`n")
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return [BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($text))).Replace('-', '').ToLowerInvariant() }
    finally { $sha.Dispose() }
}
$original = '62a9b66d05e064dc14dc309ea561cf3c41f4b6798c23ded2d89675e10d8bfecb'
$patched = 'b5dd878efa878443ce14cf40e648caa5d65b25fac7778f9bd1a3d93f234a0f47'
$current = Get-SourceHash $file
if ($current -eq $original) {
    & git -C $source apply --check --ignore-space-change $patch
    if ($LASTEXITCODE -ne 0) { throw 'JNI patch does not match pinned SDK source' }
    & git -C $source apply --ignore-space-change $patch
    if ($LASTEXITCODE -ne 0) { throw 'JNI patch failed' }
} elseif ($current -ne $patched) {
    throw 'Unexpected local changes to SDK JNI source; refusing to overwrite them'
}
if ((Get-SourceHash $file) -ne $patched) { throw 'Patched SDK source checksum mismatch' }
Write-Output 'Verified libzt JNI safe-buffer patch'
