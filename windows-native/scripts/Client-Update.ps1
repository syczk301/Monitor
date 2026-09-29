param(
    [string]$CurrentExe,
    [string]$CurrentVersion = '4.8.2',
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
        $script:Progress.Visible = $true
        $script:Progress.Style = $(if ($script:Installing) { 'Marquee' } else { 'Continuous' })
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
    $client.Encoding = [Text.Encoding]::UTF8
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

# The form is shared by every update state; network waits pump messages so it stays responsive.
function New-UpdateWindow {
    Add-Type -AssemblyName System.Windows.Forms
    Add-Type -AssemblyName System.Drawing
    [Windows.Forms.Application]::EnableVisualStyles()
    $script:UiState = 'checking'
    $script:Busy = $false
    $script:Candidate = $null
    $script:Cancelled = $false
    $script:Installing = $false
    $script:Form = New-Object Windows.Forms.Form
    $script:Form.Text = '智能监控 · 检查更新'
    $script:Form.Font = New-Object Drawing.Font('Microsoft YaHei UI', 10)
    $script:Form.AutoScaleDimensions = New-Object Drawing.SizeF(96, 96)
    $script:Form.AutoScaleMode = 'Dpi'
    $script:Form.ClientSize = New-Object Drawing.Size(620, 484)
    $script:Form.BackColor = [Drawing.Color]::White
    $script:Form.ForeColor = [Drawing.Color]::FromArgb(30, 41, 59)
    $script:Form.StartPosition = 'CenterScreen'
    $script:Form.FormBorderStyle = 'FixedDialog'
    $script:Form.MaximizeBox = $false
    $script:Form.MinimizeBox = $true
    if ($CurrentExe -and (Test-Path -LiteralPath $CurrentExe)) {
        try { $script:Form.Icon = [Drawing.Icon]::ExtractAssociatedIcon($CurrentExe) } catch { }
    }

    $brand = New-Object Windows.Forms.Label
    $brand.Text = 'CAMERA MONITOR'
    $brand.Font = New-Object Drawing.Font('Segoe UI', 9, [Drawing.FontStyle]::Bold)
    $brand.ForeColor = [Drawing.Color]::FromArgb(37, 99, 235)
    $brand.SetBounds(28, 22, 560, 24)

    $script:Title = New-Object Windows.Forms.Label
    $script:Title.Font = New-Object Drawing.Font('Microsoft YaHei UI', 19, [Drawing.FontStyle]::Bold)
    $script:Title.SetBounds(25, 53, 567, 43)
    $script:Label = New-Object Windows.Forms.Label
    $script:Label.ForeColor = [Drawing.Color]::FromArgb(71, 85, 105)
    $script:Label.SetBounds(28, 106, 564, 48)

    $script:VersionLabel = New-Object Windows.Forms.Label
    $script:VersionLabel.BackColor = [Drawing.Color]::FromArgb(241, 245, 249)
    $script:VersionLabel.Padding = New-Object Windows.Forms.Padding(12, 0, 0, 0)
    $script:VersionLabel.TextAlign = 'MiddleLeft'
    $script:VersionLabel.SetBounds(28, 164, 564, 42)

    $script:Notes = New-Object Windows.Forms.TextBox
    $script:Notes.Multiline = $true
    $script:Notes.ReadOnly = $true
    $script:Notes.ScrollBars = 'Vertical'
    $script:Notes.BorderStyle = 'None'
    $script:Notes.BackColor = [Drawing.Color]::White
    $script:Notes.ForeColor = [Drawing.Color]::FromArgb(71, 85, 105)
    $script:Notes.SetBounds(28, 224, 564, 132)
    $script:Notes.TabIndex = 2
    $script:Notes.AccessibleName = '更新说明与详细信息'

    $script:Progress = New-Object Windows.Forms.ProgressBar
    $script:Progress.SetBounds(28, 374, 564, 6)
    $script:Progress.MarqueeAnimationSpeed = 25
    $script:Progress.TabStop = $false

    $footer = New-Object Windows.Forms.Panel
    $footer.BackColor = [Drawing.Color]::FromArgb(248, 250, 252)
    $footer.SetBounds(0, 402, 620, 82)
    $script:Hint = New-Object Windows.Forms.Label
    $script:Hint.Font = New-Object Drawing.Font('Microsoft YaHei UI', 9)
    $script:Hint.ForeColor = [Drawing.Color]::FromArgb(100, 116, 139)
    $script:Hint.SetBounds(28, 15, 265, 53)
    $script:Secondary = New-Object Windows.Forms.Button
    $script:Secondary.Text = '关闭'
    $script:Secondary.SetBounds(326, 22, 112, 38)
    $script:Secondary.FlatStyle = 'Flat'
    $script:Secondary.FlatAppearance.BorderColor = [Drawing.Color]::FromArgb(203, 213, 225)
    $script:Secondary.BackColor = [Drawing.Color]::White
    $script:Secondary.TabIndex = 1
    $script:Primary = New-Object Windows.Forms.Button
    $script:Primary.SetBounds(450, 22, 142, 38)
    $script:Primary.FlatStyle = 'Flat'
    $script:Primary.FlatAppearance.BorderSize = 0
    $script:Primary.BackColor = [Drawing.Color]::FromArgb(37, 99, 235)
    $script:Primary.ForeColor = [Drawing.Color]::White
    $script:Primary.TabIndex = 0
    $footer.Controls.AddRange(@($script:Hint, $script:Secondary, $script:Primary))
    $script:Form.Controls.AddRange(@($brand, $script:Title, $script:Label, $script:VersionLabel, $script:Notes, $script:Progress, $footer))
    $script:Form.AcceptButton = $script:Primary
    $script:Form.CancelButton = $script:Secondary
    $script:Secondary.add_Click({ if (-not $script:Installing) { $script:Form.Close() } })
    $script:Primary.add_Click({
        if ($script:Busy) { return }
        if ($script:UiState -eq 'available') { Invoke-UpdateInstall }
        elseif ($script:UiState -eq 'complete') { $script:Form.Close() }
        else { Invoke-UpdateCheck }
    })
    $script:Form.add_FormClosing({
        param($sender, $eventArgs)
        if ($script:Installing) { $eventArgs.Cancel = $true }
        else { $script:Cancelled = $true }
    })
    Set-UpdateView 'checking'
}

function Set-UpdateView([string]$State, [string]$Details = '') {
    $script:UiState = $State
    $script:VersionLabel.Text = "当前版本  v$CurrentVersion"
    $script:Title.ForeColor = [Drawing.Color]::FromArgb(30, 41, 59)
    $script:Progress.Visible = $false
    $script:Primary.Enabled = $true
    $script:Secondary.Enabled = $true
    $script:Secondary.Text = '关闭'
    $script:Primary.Text = '重新检查'
    $script:Notes.Text = ''
    $script:Hint.Text = "升级时会短暂重启客户端。"
    switch ($State) {
        'checking' {
            $script:Title.Text = '正在检查更新'
            $script:Label.Text = '正在获取最新稳定版本，请稍候…'
            $script:Notes.Text = "检查更新不会中断当前录像。"
            $script:Progress.Style = 'Marquee'
            $script:Progress.Visible = $true
            $script:Primary.Text = '正在检查…'
            $script:Primary.Enabled = $false
            $script:Secondary.Text = '取消'
        }
        'available' {
            $script:Title.Text = '发现新版本'
            $script:Label.Text = '新版已准备好。查看更新内容后，即可下载并升级。'
            $script:VersionLabel.Text = "当前版本  v$CurrentVersion     →     最新版本  v$($script:Candidate.Version)"
            $notes = $script:Candidate.Notes
            if ([string]::IsNullOrWhiteSpace($notes)) { $notes = '此版本暂无更新说明。' }
            $script:Notes.Text = "更新说明`r`n`r`n" + ($notes -replace '\r?\n', "`r`n")
            $script:Primary.Text = '下载并升级'
            $script:Secondary.Text = '暂不升级'
            $script:Hint.Text = "升级前会保存当前录像。`r`n保留现有配置和证书。"
        }
        'latest' {
            $script:Title.Text = '当前已是最新版本'
            $script:Title.ForeColor = [Drawing.Color]::FromArgb(21, 128, 61)
            $script:Label.Text = '你正在使用最新稳定版本，无需更新。'
            $script:Notes.Text = "当前录像与监控服务继续运行。"
            $script:Hint.Text = '已完成在线检查'
        }
        'ahead' {
            $script:Title.Text = '当前版本较新'
            $script:Label.Text = '本机版本高于在线稳定版，无需降级。'
            $script:Notes.Text = '后续发布更高版本时，可以在这里升级。'
            $script:Hint.Text = '已完成在线检查'
        }
        'unavailable' {
            $script:Title.Text = '暂未找到可用更新'
            $script:Label.Text = '更新源暂未提供可用的稳定版本，请稍后重新检查。'
            $script:Notes.Text = '当前版本可以继续使用。'
        }
        'downloading' {
            $script:Title.Text = '正在下载更新'
            $script:Label.Text = '正在连接下载服务器…'
            $script:Notes.Text = "下载期间可继续录像。`r`n下载完成后将校验文件、保存录像并重启客户端。"
            $script:Primary.Text = '正在下载…'
            $script:Primary.Enabled = $false
            $script:Secondary.Text = '取消下载'
            $script:Progress.Style = 'Continuous'
            $script:Progress.Value = 0
            $script:Progress.Visible = $true
        }
        'installing' {
            $script:Title.Text = '正在安装更新'
            $script:Label.Text = '正在保存当前录像，请稍候…'
            $script:Notes.Text = "客户端将短暂重启。`r`n完成后会自动恢复监控服务。"
            $script:Primary.Text = '正在安装…'
            $script:Primary.Enabled = $false
            $script:Secondary.Enabled = $false
            $script:Hint.Text = '请等待安装完成'
            $script:Progress.Style = 'Marquee'
            $script:Progress.Visible = $true
        }
        'complete' {
            $script:Title.Text = '更新完成'
            $script:Title.ForeColor = [Drawing.Color]::FromArgb(21, 128, 61)
            $script:Label.Text = "v$($script:Candidate.Version) 已启动，可以继续使用。"
            $script:VersionLabel.Text = "当前版本  v$($script:Candidate.Version)"
            $script:Notes.Text = '原有配置和证书已保留，旧版程序已备份。'
            $script:Primary.Text = '完成'
            $script:Secondary.Visible = $false
            $script:Hint.Text = '客户端启动检查通过'
        }
        'error' {
            $script:Title.Text = '更新未完成'
            $script:Title.ForeColor = [Drawing.Color]::FromArgb(185, 28, 28)
            $script:Label.Text = '请查看下方详情，确认网络连接后重试。'
            $script:Notes.Text = "$Details`r`n`r`n日志位置：`r`n$script:LogPath"
            $script:Primary.Text = '重试'
            $script:Hint.Text = '详细信息可选中复制'
        }
    }
}

function Invoke-UpdateCheck {
    if ($script:Busy) { return }
    $script:Busy = $true
    try {
        Set-UpdateView 'checking'
        [Windows.Forms.Application]::DoEvents()
        if ($script:Cancelled) { return }
        Write-UpdateLog "Checking for updates from $CurrentVersion"
        $release = Get-LatestRelease
        if ($script:Cancelled) { return }
        $script:Candidate = Get-UpdateCandidate $release $CurrentVersion
        if ($script:Candidate) { Set-UpdateView 'available' }
        elseif ($null -eq $release) { Set-UpdateView 'unavailable' }
        elseif ((ConvertTo-ReleaseVersion $release.tag_name) -lt (ConvertTo-ReleaseVersion $CurrentVersion)) { Set-UpdateView 'ahead' }
        else { Set-UpdateView 'latest' }
    } catch {
        if (-not $script:Cancelled) {
            Write-UpdateLog "ERROR: $_"
            Set-UpdateView 'error' ([string]$_)
        }
    } finally { $script:Busy = $false }
}

function Invoke-UpdateInstall {
    if ($script:Busy -or $null -eq $script:Candidate) { return }
    $script:Busy = $true
    try {
        Set-UpdateView 'downloading'
        [Windows.Forms.Application]::DoEvents()
        if ($script:Cancelled) { return }
        $working = Join-Path ([IO.Path]::GetTempPath()) ('CameraMonitor-download-' + [Guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Path $working | Out-Null
        $payload = Join-Path $working 'CameraMonitor.exe'
        $checksum = Join-Path $working 'CameraMonitor.exe.sha256'
        Receive-UpdateAsset $script:Candidate.Checksum $checksum
        if ($script:Cancelled) { return }
        Receive-UpdateAsset $script:Candidate.Executable $payload
        Test-UpdatePayload $payload $checksum
        if ($script:Cancelled) { return }
        $script:Installing = $true
        $script:Form.ControlBox = $false
        Set-UpdateView 'installing'
        Install-ClientUpdate $script:Candidate $payload $CurrentExe $ParentId
        Set-UpdateView 'complete'
    } catch {
        if (-not $script:Cancelled) {
            Write-UpdateLog "ERROR: $_"
            Set-UpdateView 'error' ([string]$_)
        }
    } finally {
        $script:Installing = $false
        $script:Busy = $false
        if (-not $script:Form.IsDisposed) { $script:Form.ControlBox = $true }
    }
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
    New-UpdateWindow
    $script:Form.add_Shown({ Invoke-UpdateCheck })
    $null = $script:Form.ShowDialog()
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
