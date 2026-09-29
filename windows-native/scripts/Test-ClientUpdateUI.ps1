param([string]$OutputDirectory = (Join-Path $PSScriptRoot '..\..\.test-runs\update-ui'))
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Client-Update.ps1') -LibraryOnly
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$script:Passed = 0
function Assert($Condition, [string]$Message) {
    if (-not $Condition) { throw "FAIL: $Message" }
    $script:Passed++
}
function Save-View([string]$Name) {
    $script:Form.Refresh()
    [Windows.Forms.Application]::DoEvents()
    $bitmap = New-Object Drawing.Bitmap($script:Form.Width, $script:Form.Height)
    try {
        $script:Form.DrawToBitmap($bitmap, (New-Object Drawing.Rectangle(0, 0, $bitmap.Width, $bitmap.Height)))
        $bitmap.Save((Join-Path $OutputDirectory "$Name.png"), [Drawing.Imaging.ImageFormat]::Png)
    } finally { $bitmap.Dispose() }
}
$version = '99.0.0'
$prefix = "https://github.com/syczk301/Monitor/releases/download/v$version/"
$script:MockRelease = [pscustomobject]@{
    draft = $false; prerelease = $false; tag_name = "v$version"
    body = "托盘菜单与更新体验改进。`n" + ("中文更新说明：录像和配置会保留。`n" * 30)
    assets = @(
        [pscustomobject]@{name='CameraMonitor.exe';size=4096;browser_download_url=($prefix+'CameraMonitor.exe')},
        [pscustomobject]@{name='CameraMonitor.exe.sha256';size=84;browser_download_url=($prefix+'CameraMonitor.exe.sha256')}
    )
}
function Get-LatestRelease { return $script:MockRelease }
try {
    New-UpdateWindow
    $script:Form.StartPosition = 'Manual'
    $script:Form.Location = New-Object Drawing.Point(-3000, -3000)
    $script:Form.ShowInTaskbar = $false
    $script:Form.Show()
    Assert (-not $script:Primary.Enabled) 'checking blocks duplicate requests'
    Save-View 'checking'
    Invoke-UpdateCheck
    Assert ($script:UiState -eq 'available') 'new release renders in same window'
    Assert ($script:Notes.Text.Contains('中文更新说明')) 'Chinese release notes preserved'
    Assert ($script:Notes.Text.Length -gt 500) 'long release notes not truncated'
    Save-View 'available'
    $script:MockRelease.tag_name = "v$CurrentVersion"
    Invoke-UpdateCheck
    Assert ($script:UiState -eq 'latest') 'same version reports latest'
    Save-View 'latest'
    $script:MockRelease.tag_name = 'v0.0.1'
    Invoke-UpdateCheck
    Assert ($script:UiState -eq 'ahead') 'local newer build never offers downgrade'
    $script:MockRelease = $null
    Invoke-UpdateCheck
    Assert ($script:UiState -eq 'unavailable') 'missing release is not latest'
    function Get-LatestRelease { throw '模拟网络连接失败，请稍后重试。' }
    Invoke-UpdateCheck
    Assert ($script:UiState -eq 'error' -and $script:Primary.Enabled) 'network failure permits retry'
    Assert (-not $script:Busy) 'failure clears busy guard'
    Save-View 'error'
    Set-UpdateView 'downloading'
    Show-UpdateProgress '正在下载更新：3.5 / 7.1 MB' 49
    Assert ($script:Progress.Value -eq 49 -and -not $script:Primary.Enabled) 'download progress and duplicate-click guard'
    Save-View 'downloading'
    $script:Installing = $true
    Set-UpdateView 'installing'
    $script:Form.Close()
    Assert (-not $script:Form.IsDisposed -and -not $script:Cancelled) 'close blocked during installation'
    Assert (-not $script:Secondary.Enabled) 'cancel disabled during installation'
    Save-View 'installing'
    $script:Installing = $false
    $script:Candidate = [pscustomobject]@{Version='99.0.0';Notes='测试'}
    Set-UpdateView 'complete'
    Assert ($script:Primary.Text -eq '完成') 'successful install has completion action'
    Save-View 'complete'
    $script:Form.Scale((New-Object Drawing.SizeF(1.5, 1.5)))
    Save-View 'complete-scaled'
    $script:Primary.PerformClick()
    Assert ($script:Cancelled) 'completion button closes window'
    $client = New-UpdateWebClient
    try { Assert ($client.Encoding.WebName -eq 'utf-8') 'release response uses UTF-8' }
    finally { $client.Dispose() }

    # Exercise cancellation through the real message-pumping wait; no real network or client replacement.
    New-UpdateWindow
    $script:Form.ShowInTaskbar = $false
    $script:Form.StartPosition = 'Manual'
    $script:Form.Location = New-Object Drawing.Point(-3000, -3000)
    $script:Form.Show()
    function Get-LatestRelease {
        $script:Secondary.PerformClick()
        return $null
    }
    Invoke-UpdateCheck
    Assert ($script:Cancelled -and -not $script:Busy) 'closing check exits without error state'
    Write-Host "PASS: $script:Passed UI checks. Renders: $OutputDirectory"
} finally {
    $script:Installing = $false
    if ($script:Form) { $script:Form.Dispose() }
}
