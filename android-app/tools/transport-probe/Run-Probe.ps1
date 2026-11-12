[CmdletBinding()]
param(
    [string]$LibztSource,
    [string]$GoExe,
    [string]$GoPath,
    [string]$WorkDir,
    [string]$VcVarsPath,
    [switch]$FullMatrix
)
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..'))
if (!$LibztSource) { $LibztSource = Join-Path $repoRoot '.test-runs\libzt-source' }
if (!$GoExe) { $GoExe = Join-Path $repoRoot '.test-runs\tailscale-audit\toolchain\go\bin\go.exe' }
if (!$GoPath) { $GoPath = Join-Path $repoRoot '.test-runs\tailscale-tools\gopath' }
if (!$WorkDir) { $WorkDir = Join-Path $repoRoot ('.test-runs\transport-probe-' + (Get-Date -Format 'yyyyMMdd-HHmmss')) }
if (!$VcVarsPath) {
    $vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\Installer\vswhere.exe'
    if (!(Test-Path -LiteralPath $vswhere)) { throw 'Visual Studio Build Tools with C++ is required; pass -VcVarsPath if installed elsewhere.' }
    $installation = & $vswhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
    if (!$installation) { throw 'Visual Studio C++ build tools were not found.' }
    $VcVarsPath = Join-Path $installation 'VC\Auxiliary\Build\vcvars64.bat'
}
$LibztSource = [IO.Path]::GetFullPath($LibztSource)
$GoExe = [IO.Path]::GetFullPath($GoExe)
$WorkDir = [IO.Path]::GetFullPath($WorkDir)
$VcVarsPath = [IO.Path]::GetFullPath($VcVarsPath)
foreach ($path in @($LibztSource,$GoExe,$WorkDir,$VcVarsPath,$PSScriptRoot)) {
    if ($path -match '["&|<>^%\r\n]') { throw "Unsupported command-line character in path: $path" }
}
foreach ($path in @($GoExe,$VcVarsPath,(Join-Path $LibztSource 'src\lwipopts.h'),(Join-Path $LibztSource 'ext\lwip\src\api\sockets.c'))) {
    if (!(Test-Path -LiteralPath $path)) { throw "Missing prerequisite: $path" }
}
$pinned = 'a707ea6ae0910efdc1125d04758c411e2e9ea4f9'
if (Test-Path -LiteralPath (Join-Path $LibztSource '.git')) {
    $revision = & git -C $LibztSource rev-parse HEAD
    if ($LASTEXITCODE -ne 0 -or $revision -ne $pinned) { throw "Expected the SDK's pinned libzt revision $pinned; found $revision" }
} else {
    Write-Warning "Source archive has no .git metadata; confirm it is libzt $pinned."
}
if (Test-Path -LiteralPath $WorkDir) { throw "Use a new empty output path; already exists: $WorkDir" }
New-Item -ItemType Directory -Path $WorkDir,(Join-Path $WorkDir 'include\arch') -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $repoRoot 'android-app\tailscale\go.mod'),(Join-Path $repoRoot 'android-app\tailscale\go.sum') -Destination $WorkDir
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'probe_test.go'),(Join-Path $PSScriptRoot 'stack_link_test.go') -Destination $WorkDir
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'include\lwipopts.h') -Destination (Join-Path $WorkDir 'include')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'include\arch\cc.h') -Destination (Join-Path $WorkDir 'include\arch')
Copy-Item -LiteralPath (Join-Path $LibztSource 'src\lwipopts.h') -Destination (Join-Path $WorkDir 'include\production_lwipopts.h')

# Instrument a generated copy, never the SDK source tree. The first mailbox is
# tcpip_thread's mailbox; subsequent mailboxes belong to this one TCP netconn.
$sysArchPath = Join-Path $LibztSource 'ext\lwip-contrib\ports\win32\sys_arch.c'
$sysArch = Get-Content -LiteralPath $sysArchPath -Raw
$sysArch = $sysArch.Replace('#include <lwip/tcpip.h>', "#include <lwip/tcpip.h>`nextern void probe_mbox_new(void *q);`nextern void probe_mbox_full(void *q);")
$sysArch = [regex]::Replace($sysArch, '(sys_mbox_new\(sys_mbox_t \*mbox, int size\)\s*\{)', '$1' + "`n  probe_mbox_new(mbox);")
$sysArch = $sysArch.Replace('if (new_head == q->tail) {', "if (new_head == q->tail) {`n    probe_mbox_full(q);")
if ($sysArch -notmatch 'probe_mbox_new\(mbox\)' -or $sysArch -notmatch 'probe_mbox_full\(q\)') { throw 'Pinned sys_arch instrumentation sites were not found.' }
Set-Content -LiteralPath (Join-Path $WorkDir 'sys_arch_instrumented.c') -Value $sysArch -Encoding utf8

$sourceFiles = @(
    (Join-Path $PSScriptRoot 'csource\lwip_receiver_bsd.c'),
    (Join-Path $PSScriptRoot 'csource\bsd_wire.c'),
    (Join-Path $WorkDir 'sys_arch_instrumented.c'),
    (Join-Path $LibztSource 'ext\lwip\src\netif\ethernet.c')
)
foreach ($subdir in @('core','core\ipv4','core\ipv6','api')) {
    $sourceFiles += Get-ChildItem -LiteralPath (Join-Path $LibztSource "ext\lwip\src\$subdir") -Filter '*.c' | ForEach-Object { $_.FullName }
}
$env:GOPATH = $GoPath
$env:GOCACHE = Join-Path $repoRoot '.test-runs\tailscale-tools\gocache'
$env:GOTELEMETRY = 'off'
$env:LINK_DELAY_MS = '300'
$env:LINK_LOSS = '0'
$env:LWIP_LABEL = 'm128'
$cases = @(@{Slots=128;Mode='1020';Name='precise-sleep20'},@{Slots=128;Mode='50';Name='select50'})
if ($FullMatrix) { $cases += @(@{Slots=128;Mode='0';Name='blocking'},@{Slots=128;Mode='1002';Name='precise-sleep2'},@{Slots=512;Mode='1020';Name='precise-sleep20-m512'}) }
$summary = @()
$lastSlots = 0
Push-Location $WorkDir
try {
    foreach ($case in $cases) {
        if ($lastSlots -ne $case.Slots) {
            $arguments = @('/nologo','/O2','/W1','/D_CRT_SECURE_NO_WARNINGS',"/DMAX_QUEUE_ENTRIES=$($case.Slots)",'/I "include"',('/I "' + (Join-Path $LibztSource 'ext\lwip-contrib\ports\win32\include') + '"'),('/I "' + (Join-Path $LibztSource 'ext\lwip\src\include') + '"'))
            $arguments += $sourceFiles | ForEach-Object { '"' + $_ + '"' }
            $arguments += @('/Fe:lwip_receiver_bsd.exe','/link','ws2_32.lib','advapi32.lib')
            Set-Content -LiteralPath 'build.rsp' -Value $arguments -Encoding ascii
            Set-Content -LiteralPath 'build.cmd' -Value @('@echo off',('call "' + $VcVarsPath + '" >nul'),'if errorlevel 1 exit /b %errorlevel%','cl @build.rsp') -Encoding ascii
            & cmd.exe /d /c build.cmd *> "build-m$($case.Slots).log"
            if ($LASTEXITCODE -ne 0) { throw "C build failed; see build-m$($case.Slots).log" }
            $lastSlots = $case.Slots
        }
        $env:LWIP_BSD = $case.Mode
        $env:LWIP_LABEL = "m$($case.Slots)"
        & $GoExe test -v -run TestLWIPReceiver -count=1 -timeout 40s *> "run-$($case.Name).log"
        if ($LASTEXITCODE -ne 0) { throw "Probe failed; see run-$($case.Name).log" }
        $receiverLog = "lwip-bsd$($case.Mode)-m$($case.Slots)-delay300-loss0.0000.log"
        $lines = Get-Content -LiteralPath $receiverLog
        $all = @($lines | Where-Object { $_.StartsWith('{') } | ConvertFrom-Json)
        $steady = @($all | Where-Object { $_.second -ge 11 -and $_.second -lt 21 })
        $before21 = @($all | Where-Object { $_.second -lt 21 })
        $last = $before21[-1]
        $waits = ($before21 | Measure-Object EAGAIN -Sum).Sum
        $summary += [pscustomobject]@{ Mode=$case.Name; Slots=$case.Slots; Samples=$steady.Count; Mbps=($steady|Measure-Object Mbps -Average).Average; ActualWaitMs=if($waits){$last.cumulativeWaitUs/$waits/1000}else{0}; RecvMailboxFull=$last.recvMboxFull; Final=$lines[-1] }
        Write-Output "Completed $($case.Name)"
    }
    $summary | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath 'summary.json' -Encoding utf8
    $summary | Format-Table Mode,Slots,Samples,Mbps,ActualWaitMs,RecvMailboxFull
    Write-Output "Logs and generated build files: $WorkDir"
} finally { Pop-Location }
