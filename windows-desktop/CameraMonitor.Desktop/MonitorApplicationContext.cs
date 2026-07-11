using System.Diagnostics;
using System.Net.Http.Json;
using System.Text.Json.Serialization;

namespace CameraMonitor.Desktop;

internal sealed class MonitorApplicationContext : ApplicationContext
{
    private readonly AppPaths _paths;
    private readonly BackendService _backend;
    private readonly MainForm _form;
    private readonly NotifyIcon _trayIcon;
    private readonly ToolStripMenuItem _startMenuItem;
    private readonly ToolStripMenuItem _stopMenuItem;
    private readonly ToolStripMenuItem _autoStartMenuItem;
    private readonly System.Windows.Forms.Timer _statusTimer;
    private readonly HttpClient _httpClient = new() { Timeout = TimeSpan.FromSeconds(1) };
    private bool _exiting;
    private bool _serviceReachable;

    public MonitorApplicationContext(string[] args)
    {
        _paths = AppPaths.Discover();
        _backend = new BackendService(_paths);
        _backend.OutputReceived += OnBackendOutputReceived;

        _form = new MainForm(_paths);
        _form.StartClicked += (_, _) => StartBackend();
        _form.StopClicked += async (_, _) => await StopBackendAsync();
        _form.OpenDashboardClicked += (_, _) => OpenUrl(_paths.DashboardUrl);
        _form.OpenConfigClicked += (_, _) => OpenFile(_paths.EnvFile);
        _form.OpenLogClicked += (_, _) => OpenFile(_paths.DesktopLogFile);
        _form.OpenDataClicked += (_, _) => OpenFile(_paths.DataDirectory);
        _form.AutoStartChanged += (_, enabled) => SetAutoStart(enabled);

        _startMenuItem = new ToolStripMenuItem("启动服务", null, (_, _) => StartBackend());
        _stopMenuItem = new ToolStripMenuItem("停止服务", null, async (_, _) => await StopBackendAsync());
        _autoStartMenuItem = new ToolStripMenuItem("开机自启动");
        _autoStartMenuItem.Click += (_, _) => SetAutoStart(!_autoStartMenuItem.Checked);

        var menu = new ContextMenuStrip();
        menu.Items.Add("打开监控面板", null, (_, _) => OpenUrl(_paths.DashboardUrl));
        menu.Items.Add("显示主窗口", null, (_, _) => ShowMainWindow());
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add(_startMenuItem);
        menu.Items.Add(_stopMenuItem);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add(_autoStartMenuItem);
        menu.Items.Add("打开日志", null, (_, _) => OpenFile(_paths.DesktopLogFile));
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("退出", null, async (_, _) => await ExitAsync());

        _trayIcon = new NotifyIcon
        {
            Icon = LoadIcon(_paths),
            Text = "智能监控",
            Visible = true,
            ContextMenuStrip = menu,
        };
        _trayIcon.DoubleClick += (_, _) => OpenUrl(_paths.DashboardUrl);

        _statusTimer = new System.Windows.Forms.Timer { Interval = 2000 };
        _statusTimer.Tick += async (_, _) => await RefreshStatusAsync();
        _statusTimer.Start();

        _form.SetAutoStartChecked(WindowsStartup.IsEnabled());
        _autoStartMenuItem.Checked = WindowsStartup.IsEnabled();

        StartBackend();

        if (!args.Any(arg => arg.Equals("--minimized", StringComparison.OrdinalIgnoreCase)))
        {
            ShowMainWindow();
        }
    }

    private void StartBackend()
    {
        try
        {
            _backend.Start();
            AppendLog("已请求启动后端服务。");
            UpdateUi("正在检查...");
        }
        catch (Exception ex)
        {
            AppendLog($"启动后端服务失败：{ex.Message}");
            MessageBox.Show(ex.Message, "智能监控", MessageBoxButtons.OK, MessageBoxIcon.Error);
            UpdateUi("启动失败");
        }
    }

    private async Task StopBackendAsync()
    {
        await _backend.StopAsync();
        UpdateUi("已停止");
    }

    private async Task RefreshStatusAsync()
    {
        if (_exiting)
        {
            return;
        }

        try
        {
            var health = await _httpClient.GetFromJsonAsync<HealthResponse>($"{_paths.DashboardUrl}/api/health");
            _serviceReachable = health is not null;
            UpdateUi(health is null ? "没有健康检查响应" : $"正常，FPS={health.Fps:0.0}，目标={health.TrackedTargets}");
        }
        catch
        {
            _serviceReachable = false;
            UpdateUi(_backend.IsRunning ? "正在启动或暂不可用" : "已停止");
        }
    }

    private void UpdateUi(string healthText)
    {
        var running = _backend.IsRunning || _serviceReachable;
        _form.UpdateStatus(running, healthText, _backend.ProcessId);
        _trayIcon.Text = running ? "智能监控 - 运行中" : "智能监控 - 已停止";
        _startMenuItem.Enabled = !running;
        _stopMenuItem.Enabled = _backend.IsRunning;
    }

    private void SetAutoStart(bool enabled)
    {
        try
        {
            WindowsStartup.SetEnabled(enabled);
            var actual = WindowsStartup.IsEnabled();
            _autoStartMenuItem.Checked = actual;
            _form.SetAutoStartChecked(actual);
            AppendLog(actual ? "已启用开机自启动。" : "已关闭开机自启动。");
        }
        catch (Exception ex)
        {
            AppendLog($"更新开机自启动失败：{ex.Message}");
            MessageBox.Show(ex.Message, "智能监控", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
    }

    private void OnBackendOutputReceived(object? sender, string message)
    {
        AppendLog(message);
    }

    private void AppendLog(string message)
    {
        LogWriter.Append(_paths, message);
        _form.AppendLog(message);
    }

    private void ShowMainWindow()
    {
        if (_form.Visible)
        {
            _form.Activate();
            return;
        }

        _form.Show();
        _form.Activate();
    }

    private static void OpenUrl(string url)
    {
        Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });
    }

    private static void OpenFile(string path)
    {
        var looksLikeFile = Path.HasExtension(path) || Path.GetFileName(path).StartsWith('.');

        if (looksLikeFile && !File.Exists(path))
        {
            var parent = Path.GetDirectoryName(path);
            if (!string.IsNullOrEmpty(parent))
            {
                Directory.CreateDirectory(parent);
            }

            File.WriteAllText(path, string.Empty);
        }

        if (!looksLikeFile && !Directory.Exists(path))
        {
            Directory.CreateDirectory(path);
        }

        Process.Start(new ProcessStartInfo(path) { UseShellExecute = true });
    }

    private async Task ExitAsync()
    {
        _exiting = true;
        _trayIcon.Visible = false;
        _statusTimer.Stop();
        await StopBackendAsync();
        _form.AllowClose = true;
        _form.Close();
        ExitThread();
    }

    private static Icon LoadIcon(AppPaths paths)
    {
        var candidates = new[]
        {
            Path.Combine(AppContext.BaseDirectory, "assets", "tray_icon.ico"),
            Path.Combine(paths.RepositoryRoot, "assets", "tray_icon.ico"),
        };

        foreach (var path in candidates)
        {
            if (File.Exists(path))
            {
                return new Icon(path);
            }
        }

        return Icon.ExtractAssociatedIcon(Application.ExecutablePath) ?? SystemIcons.Application;
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            _statusTimer.Dispose();
            _trayIcon.Dispose();
            _backend.Dispose();
            _httpClient.Dispose();
            _form.Dispose();
        }

        base.Dispose(disposing);
    }

    private sealed class HealthResponse
    {
        [JsonPropertyName("fps")]
        public double Fps { get; set; }

        [JsonPropertyName("tracked_targets")]
        public int TrackedTargets { get; set; }
    }
}
