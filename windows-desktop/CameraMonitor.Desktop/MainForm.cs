namespace CameraMonitor.Desktop;

internal sealed class MainForm : Form
{
    private readonly Label _processStatusValue = new();
    private readonly Label _healthStatusValue = new();
    private readonly Label _pidValue = new();
    private readonly TextBox _logBox = new();
    private readonly Button _startButton = new();
    private readonly Button _stopButton = new();
    private readonly CheckBox _autoStartCheckBox = new();

    public MainForm(AppPaths paths)
    {
        Text = "智能监控";
        StartPosition = FormStartPosition.CenterScreen;
        MinimumSize = new Size(760, 520);
        Size = new Size(860, 600);

        var root = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            Padding = new Padding(16),
            RowCount = 4,
            ColumnCount = 1,
        };
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        root.RowStyles.Add(new RowStyle(SizeType.Percent, 100));

        var title = new Label
        {
            Text = "智能监控 Windows 主程序",
            AutoSize = true,
            Font = new Font(Font.FontFamily, 18, FontStyle.Bold),
            Margin = new Padding(0, 0, 0, 12),
        };

        var statusGrid = new TableLayoutPanel
        {
            AutoSize = true,
            Dock = DockStyle.Top,
            ColumnCount = 2,
            Margin = new Padding(0, 0, 0, 12),
        };
        statusGrid.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 120));
        statusGrid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));

        AddStatusRow(statusGrid, "后端服务", _processStatusValue);
        AddStatusRow(statusGrid, "健康状态", _healthStatusValue);
        AddStatusRow(statusGrid, "进程 ID", _pidValue);
        AddStatusRow(statusGrid, "项目目录", CreatePathLabel(paths.RepositoryRoot));
        AddStatusRow(statusGrid, "Python 环境", CreatePathLabel(paths.PythonExecutable));

        var actions = new FlowLayoutPanel
        {
            Dock = DockStyle.Top,
            AutoSize = true,
            WrapContents = true,
            Margin = new Padding(0, 0, 0, 12),
        };

        _startButton.Text = "启动服务";
        _startButton.AutoSize = true;
        _startButton.Click += (_, _) => StartClicked?.Invoke(this, EventArgs.Empty);

        _stopButton.Text = "停止服务";
        _stopButton.AutoSize = true;
        _stopButton.Click += (_, _) => StopClicked?.Invoke(this, EventArgs.Empty);

        var openDashboardButton = new Button { Text = "打开监控面板", AutoSize = true };
        openDashboardButton.Click += (_, _) => OpenDashboardClicked?.Invoke(this, EventArgs.Empty);

        var openConfigButton = new Button { Text = "打开配置", AutoSize = true };
        openConfigButton.Click += (_, _) => OpenConfigClicked?.Invoke(this, EventArgs.Empty);

        var openLogButton = new Button { Text = "打开日志", AutoSize = true };
        openLogButton.Click += (_, _) => OpenLogClicked?.Invoke(this, EventArgs.Empty);

        var openDataButton = new Button { Text = "打开数据目录", AutoSize = true };
        openDataButton.Click += (_, _) => OpenDataClicked?.Invoke(this, EventArgs.Empty);

        _autoStartCheckBox.Text = "开机自启动";
        _autoStartCheckBox.AutoSize = true;
        _autoStartCheckBox.CheckedChanged += AutoStartCheckBoxOnCheckedChanged;

        actions.Controls.AddRange(new Control[]
        {
            _startButton,
            _stopButton,
            openDashboardButton,
            openConfigButton,
            openLogButton,
            openDataButton,
            _autoStartCheckBox,
        });

        _logBox.Dock = DockStyle.Fill;
        _logBox.Multiline = true;
        _logBox.ReadOnly = true;
        _logBox.ScrollBars = ScrollBars.Vertical;
        _logBox.Font = new Font(FontFamily.GenericMonospace, 9);

        root.Controls.Add(title, 0, 0);
        root.Controls.Add(statusGrid, 0, 1);
        root.Controls.Add(actions, 0, 2);
        root.Controls.Add(_logBox, 0, 3);
        Controls.Add(root);
    }

    public event EventHandler? StartClicked;

    public event EventHandler? StopClicked;

    public event EventHandler? OpenDashboardClicked;

    public event EventHandler? OpenConfigClicked;

    public event EventHandler? OpenLogClicked;

    public event EventHandler? OpenDataClicked;

    public event EventHandler<bool>? AutoStartChanged;

    public bool AllowClose { get; set; }

    public void UpdateStatus(bool backendRunning, string healthText, int? processId)
    {
        if (InvokeRequired)
        {
            BeginInvoke(new Action(() => UpdateStatus(backendRunning, healthText, processId)));
            return;
        }

        _processStatusValue.Text = backendRunning ? "运行中" : "已停止";
        _processStatusValue.ForeColor = backendRunning ? Color.DarkGreen : Color.DarkRed;
        _healthStatusValue.Text = healthText;
        _pidValue.Text = processId?.ToString() ?? "-";
        _startButton.Enabled = !backendRunning;
        _stopButton.Enabled = backendRunning;
    }

    public void SetAutoStartChecked(bool isChecked)
    {
        if (InvokeRequired)
        {
            BeginInvoke(new Action(() => SetAutoStartChecked(isChecked)));
            return;
        }

        _autoStartCheckBox.CheckedChanged -= AutoStartCheckBoxOnCheckedChanged;
        _autoStartCheckBox.Checked = isChecked;
        _autoStartCheckBox.CheckedChanged += AutoStartCheckBoxOnCheckedChanged;
    }

    public void AppendLog(string message)
    {
        if (InvokeRequired)
        {
            BeginInvoke(new Action(() => AppendLog(message)));
            return;
        }

        _logBox.AppendText($"{DateTime.Now:HH:mm:ss} {message}{Environment.NewLine}");
    }

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        if (!AllowClose && e.CloseReason == CloseReason.UserClosing)
        {
            e.Cancel = true;
            Hide();
            return;
        }

        base.OnFormClosing(e);
    }

    private void AutoStartCheckBoxOnCheckedChanged(object? sender, EventArgs e)
    {
        AutoStartChanged?.Invoke(this, _autoStartCheckBox.Checked);
    }

    private static void AddStatusRow(TableLayoutPanel grid, string name, Control value)
    {
        var row = grid.RowCount++;
        grid.RowStyles.Add(new RowStyle(SizeType.AutoSize));

        var label = new Label
        {
            Text = name,
            AutoSize = true,
            Font = new Font(SystemFonts.MessageBoxFont ?? Control.DefaultFont, FontStyle.Bold),
            Margin = new Padding(0, 2, 12, 6),
        };

        value.Margin = new Padding(0, 2, 0, 6);
        value.AutoSize = true;

        grid.Controls.Add(label, 0, row);
        grid.Controls.Add(value, 1, row);
    }

    private static Label CreatePathLabel(string path)
    {
        return new Label
        {
            Text = path,
            AutoEllipsis = true,
            MaximumSize = new Size(680, 0),
        };
    }
}
