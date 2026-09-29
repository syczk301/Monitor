param(
    [string]$CurrentExe,
    [string]$CurrentVersion = '4.8.0',
    [int]$ParentId = 0,
    [switch]$CheckOnly,
    [switch]$LibraryOnly
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2
$script:ReleaseApi = 'https://api.github.com/repos/syczk301/Monitor/releases/latest'
$script:Cancelled = $false
$script:Installing = $false
$script:Form = $null
$script:Label = $null
$script:Progress = $null
$script:LogPath = $null

function Write-UpdateLog([string]$Message) {
    if ($script:LogPath) {
        Add-Content -LiteralPath $script:LogPath -Value (('{0:o} {1}' -f [DateTime]::Now, $Message)) -Encoding UTF8
    }
}

function Show-UpdateProgress([string]$Message, [int]$Percent = 0) {
    if ($script:Label) {
        $script:Label.Text = $Message
        $script:Progress.Value = [Math]::Max(0, [Math]::Min(100, $Percent))
        [System.Windows.Forms.Application]::DoEvents()
    }
}

function ConvertTo-ReleaseVersion([string]$Value) {
    if ($Value -notmatch '^v?(\d+)\.(\d+)\.(\d+)$') { throw "Invalid stable version: $Value" }
    return [version]($Value.TrimStart('v'))
}

function Get-UpdateCandidate($Release, [string]$InstalledVersion) {
    if ($null -eq $Release) { return $null }
    if ($Release.draft -or $Release.prerelease) { return $null }
    $version = ConvertTo-ReleaseVersion $Release.tag_name
    if ($version -le (ConvertTo-ReleaseVersion $InstalledVersion)) { return $null }
    $tag = [Uri]::EscapeDataString([string]$Release.tag_name)
    $prefix = "https://github.com/syczk301/Monitor/releases/download/$tag/"
    $assets = @{}
    foreach ($name in @('CameraMonitor.exe', 'CameraMonitor.exe.sha256')) {
        $matches = @($Release.assets | Where-Object { $_.name -ceq $name })
        if ($matches.Count -ne 1) { throw "Release must contain exactly one $name" }
        $asset = $matches[0]
        if ([string]$asset.browser_download_url -cne ($prefix + $name)) {
            throw "Unexpected update source for $name"
        }
        $limit = if ($name -eq 'CameraMonitor.exe') { 64MB } else { 1024 }
        if ([long]$asset.size -le 0 -or [long]$asset.size -gt $limit) { throw "Invalid asset size: $name" }
        $assets[$name] = $asset
    }
    return [pscustomobject]@{
        Version = $version.ToString(3)
        Notes = [string]$Release.body
        Executable = $assets['CameraMonitor.exe']
        Checksum = $assets['CameraMonitor.exe.sha256']
    }
}

function New-UpdateWebClient {
    $client = New-Object System.Net.WebClient
    $client.Headers['User-Agent'] = 'CameraMonitor-Updater/4.8'
    $client.Headers['Accept'] = 'application/vnd.github+json'
    return $client
}

function Wait-UpdateTask($Task, $Client, [int]$TimeoutSeconds = 120) {
    $timer = [Diagnostics.Stopwatch]::StartNew()
    while (-not $Task.IsCompleted) {
        if ($script:Form) { [System.Windows.Forms.Application]::DoEvents() }
        if ($script:Cancelled -or $timer.Elapsed.TotalSeconds -gt $TimeoutSeconds) {
            $Client.CancelAsync()
            throw '更新已取消或网络请求超时，原客户端未被替换。'
        }
        Start-Sleep -Milliseconds 50
    }
    return $Task.GetAwaiter().GetResult()
}

function Get-LatestRelease {
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $client = New-UpdateWebClient
    try {
        $task = $client.DownloadStringTaskAsync([Uri]$script:ReleaseApi)
        $json = Wait-UpdateTask $task $client 30
        if ($json.Length -gt 2MB) { throw 'Release response is too large' }
        return ($json | ConvertFrom-Json)
    } catch {
        $cause = $_.Exception.GetBaseException()
        if ($cause -is [Net.WebException] -and $cause.Response -and [int]$cause.Response.StatusCode -eq 404) {
            return $null
        }
        throw
    } finally { $client.Dispose() }
}

function Receive-UpdateAsset($Asset, [string]$Destination) {
    $client = New-UpdateWebClient
    try {
        $client.Headers['Accept'] = 'application/octet-stream'
        $task = $client.DownloadFileTaskAsync([Uri]$Asset.browser_download_url, $Destination)
        $timer = [Diagnostics.Stopwatch]::StartNew()
        while (-not $task.IsCompleted) {
            $length = if (Test-Path -LiteralPath $Destination) { (Get-Item -LiteralPath $Destination).Length } else { 0 }
            if ($length -gt [long]$Asset.size) { $client.CancelAsync(); throw 'Downloaded asset exceeds declared size' }
            Show-UpdateProgress ('正在下载更新：{0:N1} / {1:N1} MB' -f ($length / 1MB), ($Asset.size / 1MB)) ([int](100 * $length / $Asset.size))
            if ($script:Cancelled -or $timer.Elapsed.TotalSeconds -gt 180) {
                $client.CancelAsync(); throw '下载已取消或超时，原客户端未被替换。'
            }
            Start-Sleep -Milliseconds 80
        }
        $task.GetAwaiter().GetResult()
        if ((Get-Item -LiteralPath $Destination).Length -ne [long]$Asset.size) { throw 'Downloaded asset size mismatch' }
    } finally { $client.Dispose() }
}

function Test-UpdatePayload([string]$Executable, [string]$ChecksumFile) {
    $text = [IO.File]::ReadAllText($ChecksumFile).Trim()
    if ($text -notmatch '\A([a-fA-F0-9]{64})(?:[ \t]+\*?CameraMonitor\.exe)?\z') { throw 'Invalid SHA-256 checksum file' }
    $expected = $Matches[1]
    $actual = (Get-FileHash -LiteralPath $Executable -Algorithm SHA256).Hash
    if ($actual -ine $expected) { throw '更新文件校验失败，原客户端未被替换。' }
    $stream = [IO.File]::OpenRead($Executable)
    try {
        if ($stream.ReadByte() -ne 77 -or $stream.ReadByte() -ne 90) { throw 'Update is not a Windows executable' }
    } finally { $stream.Dispose() }
}

function Invoke-ExecutableReplacement(
    [string]$Target, [string]$Staged, [string]$Backup,
    [scriptblock]$Start, [scriptblock]$Validate, [scriptblock]$StopReplacement
) {
    $Target = [IO.Path]::GetFullPath($Target)
    $Staged = [IO.Path]::GetFullPath($Staged)
    $Backup = [IO.Path]::GetFullPath($Backup)
    $directory = [IO.Path]::GetDirectoryName($Target)
    foreach ($path in @($Staged, $Backup)) {
        if ([IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($path)) -ine $directory -or $path -ieq $Target) {
            throw 'Update staging and backup must be distinct files next to the executable'
        }
    }
    if ($Staged -ieq $Backup -or (Test-Path -LiteralPath $Backup)) { throw 'Unsafe backup path' }
    $newProcess = $null
    try { [IO.File]::Move($Target, $Backup) } catch {
        $null = & $Start $Target
        throw
    }
    try {
        [IO.File]::Move($Staged, $Target)
        $newProcess = & $Start $Target
        if (-not (& $Validate $newProcess)) { throw '新版未能通过启动检查' }
    } catch {
        $failure = $_
        if ($null -ne $newProcess) { & $StopReplacement $newProcess }
        if (Test-Path -LiteralPath $Target) { Remove-Item -LiteralPath $Target -Force }
        [IO.File]::Move($Backup, $Target)
        $null = & $Start $Target
        throw "更新失败，已恢复旧版：$failure"
    }
}

function Stop-ClientGracefully([int]$ProcessId, [string]$ExpectedPath, [string]$WindowClass = 'CameraMonitorRustTrayWindow') {
    $process = Get-Process -Id $ProcessId -ErrorAction Stop
    if ($process.Path -ine [IO.Path]::GetFullPath($ExpectedPath)) { throw 'Client process path changed' }
    if (-not ('CameraMonitorUpdateNative' -as [type])) {
        Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class CameraMonitorUpdateNative {
    [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern IntPtr FindWindow(string c, string t);
    public static IntPtr FindClientWindow(string c) { return FindWindow(c, null); }
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint p);
    [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr h, uint m, UIntPtr w, IntPtr l);
    public static bool RequestExit(IntPtr h) { return PostMessage(h, 0x111, new UIntPtr(1006), IntPtr.Zero); }
}
'@
    }
    $window = [CameraMonitorUpdateNative]::FindClientWindow($WindowClass)
    [uint32]$owner = 0
    $null = [CameraMonitorUpdateNative]::GetWindowThreadProcessId($window, [ref]$owner)
    if ($window -eq [IntPtr]::Zero -or $owner -ne $ProcessId) { throw 'Cannot find the expected client tray window' }
    if (-not [CameraMonitorUpdateNative]::RequestExit($window)) { throw 'Cannot request client shutdown' }
    $deadline = [DateTime]::UtcNow.AddSeconds(60)
    while (-not $process.HasExited -and [DateTime]::UtcNow -lt $deadline) {
        if ($script:Form) { [System.Windows.Forms.Application]::DoEvents() }
        Start-Sleep -Milliseconds 100
        $process.Refresh()
    }
    if (-not $process.HasExited) { throw '录像仍在保存，已取消替换。请稍后重试。' }
}

function Get-ClientHealthUrl {
    $settingsPath = Join-Path $env:LOCALAPPDATA 'CameraMonitor\settings.json'
    $settings = Get-Content -LiteralPath $settingsPath -Raw | ConvertFrom-Json
    $address = [string]$settings.bind_address
    if ($address -match '^0\.0\.0\.0:(\d+)$') { $address = "127.0.0.1:$($Matches[1])" }
    if ($address -match '^\[::\]:(\d+)$') { $address = "[::1]:$($Matches[1])" }
    return "https://$address/api/health"
}

function Install-ClientUpdate($Candidate, [string]$Executable, [string]$Target, [int]$ProcessId) {
    $Target = [IO.Path]::GetFullPath($Target)
    if (-not (Test-Path -LiteralPath $Target -PathType Leaf)) { throw 'Current executable is missing' }
    $directory = Split-Path -Parent $Target
    $suffix = [Guid]::NewGuid().ToString('N')
    $staged = Join-Path $directory ".CameraMonitor-$suffix.new"
    $backup = Join-Path $directory "CameraMonitor-$suffix.backup.exe"
    # Fail before stopping recording if the installation directory is not writable.
    Copy-Item -LiteralPath $Executable -Destination $staged -ErrorAction Stop
    $healthUrl = Get-ClientHealthUrl
    $expectedVersion = $Candidate.Version
    $start = { param($path) Start-Process -FilePath $path -WorkingDirectory (Split-Path -Parent $path) -WindowStyle Hidden -PassThru }
    $validate = {
        param($process)
        $deadline = [DateTime]::UtcNow.AddSeconds(40)
        while ([DateTime]::UtcNow -lt $deadline) {
            $process.Refresh()
            if ($process.HasExited) { return $false }
            try {
                $health = Invoke-RestMethod -Uri $healthUrl -TimeoutSec 2 -UseBasicParsing
                if ($health.version -eq $expectedVersion -and $health.runtime -eq 'rust-native') {
                    return $true
                }
            } catch { }
            Start-Sleep -Milliseconds 250
        }
        return $false
    }.GetNewClosure()
    $stop = {
        param($process)
        $process.Refresh()
        if (-not $process.HasExited) {
            Stop-ClientGracefully $process.Id $Target
        }
    }.GetNewClosure()
    Show-UpdateProgress '正在保存当前录像并退出旧版本…' 100
    Stop-ClientGracefully $ProcessId $Target
    Show-UpdateProgress '正在替换程序并检查新版启动状态…' 100
    Invoke-ExecutableReplacement $Target $staged $backup $start $validate $stop
    Write-UpdateLog "Updated to $expectedVersion; backup: $backup"
}

if ($LibraryOnly) { return }

if ($CheckOnly) {
    $release = Get-LatestRelease
    $candidate = Get-UpdateCandidate $release $CurrentVersion
    [pscustomobject]@{
        current_version = $CurrentVersion
        status = $(if ($null -eq $release) { 'no_release' } elseif ($candidate) { 'update_available' } else { 'up_to_date' })
        update = $candidate
    } | ConvertTo-Json -Depth 5
    return
}

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
$mutex = New-Object Threading.Mutex($false, 'Local\CameraMonitor.Updater')
$locked = $false
$working = $null
try {
    try { $locked = $mutex.WaitOne(0) } catch [Threading.AbandonedMutexException] { $locked = $true }
    if (-not $locked) {
        [System.Windows.Forms.MessageBox]::Show('更新窗口已经打开。', 'Camera Monitor') | Out-Null
        return
    }
    $logDirectory = Join-Path $env:LOCALAPPDATA 'CameraMonitor\logs'
    New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
    $script:LogPath = Join-Path $logDirectory 'update.log'
    Write-UpdateLog "Checking for updates from $CurrentVersion"
    $script:Form = New-Object System.Windows.Forms.Form
    $script:Form.Text = 'Camera Monitor 更新'
    $script:Form.ClientSize = New-Object Drawing.Size(460, 110)
    $script:Form.StartPosition = 'CenterScreen'
    $script:Form.FormBorderStyle = 'FixedDialog'
    $script:Form.MaximizeBox = $false
    $script:Label = New-Object System.Windows.Forms.Label
    $script:Label.SetBounds(18, 16, 425, 40)
    $script:Progress = New-Object System.Windows.Forms.ProgressBar
    $script:Progress.SetBounds(18, 65, 425, 20)
    $script:Form.Controls.AddRange(@($script:Label, $script:Progress))
    $script:Form.add_FormClosing({
        param($sender, $eventArgs)
        if ($script:Installing) { $eventArgs.Cancel = $true } else { $script:Cancelled = $true }
    })
    $script:Form.Show()
    Show-UpdateProgress '正在检查新版本…'
    $release = Get-LatestRelease
    $candidate = Get-UpdateCandidate $release $CurrentVersion
    if ($script:Cancelled) { return }
    if ($null -eq $candidate) {
        $message = if ($null -eq $release) { '暂未发布可用的在线更新。当前版本：' + $CurrentVersion } else { '当前已是最新稳定版：' + $CurrentVersion }
        [System.Windows.Forms.MessageBox]::Show($script:Form, $message, 'Camera Monitor') | Out-Null
        return
    }
    $notes = $candidate.Notes
    if ($notes.Length -gt 1500) { $notes = $notes.Substring(0, 1500) + '…' }
    $answer = [System.Windows.Forms.MessageBox]::Show($script:Form,
        "发现新版本 $($candidate.Version)，当前版本 $CurrentVersion。`r`n`r`n$notes`r`n`r`n是否下载并升级？升级时会保存当前录像并短暂重启客户端，现有配置和证书保留。",
        'Camera Monitor 更新', 'YesNo', 'Question')
    if ($answer -ne 'Yes') { return }
    $working = Join-Path ([IO.Path]::GetTempPath()) ('CameraMonitor-download-' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $working | Out-Null
    $payload = Join-Path $working 'CameraMonitor.exe'
    $checksum = Join-Path $working 'CameraMonitor.exe.sha256'
    Receive-UpdateAsset $candidate.Checksum $checksum
    Receive-UpdateAsset $candidate.Executable $payload
    Test-UpdatePayload $payload $checksum
    if ($script:Cancelled) { return }
    $script:Installing = $true
    $script:Form.ControlBox = $false
    Install-ClientUpdate $candidate $payload $CurrentExe $ParentId
    [System.Windows.Forms.MessageBox]::Show($script:Form, "已升级到 $($candidate.Version)，客户端已重新启动。", 'Camera Monitor') | Out-Null
} catch {
    Write-UpdateLog "ERROR: $_"
    [System.Windows.Forms.MessageBox]::Show("更新未完成：$_`r`n可稍后重试。日志位于 $script:LogPath", 'Camera Monitor 更新', 'OK', 'Error') | Out-Null
    exit 1
} finally {
    if ($script:Form) { $script:Form.Dispose() }
    if ($locked) { $mutex.ReleaseMutex() }
    $mutex.Dispose()
    # Keep downloaded files and any backup for diagnostics; never recursively delete.
}
