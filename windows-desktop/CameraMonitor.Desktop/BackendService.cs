using System.Diagnostics;

namespace CameraMonitor.Desktop;

internal sealed class BackendService : IDisposable
{
    private readonly AppPaths _paths;
    private Process? _process;
    private bool _disposed;

    public BackendService(AppPaths paths)
    {
        _paths = paths;
    }

    public event EventHandler<string>? OutputReceived;

    public bool IsRunning => _process is { HasExited: false };

    public int? ProcessId => IsRunning ? _process?.Id : null;

    public void Start()
    {
        ObjectDisposedException.ThrowIf(_disposed, this);

        if (IsRunning)
        {
            return;
        }

        Directory.CreateDirectory(_paths.DataDirectory);

        var startInfo = new ProcessStartInfo
        {
            FileName = _paths.PythonExecutable,
            WorkingDirectory = _paths.RepositoryRoot,
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
        };

        startInfo.ArgumentList.Add("-m");
        startInfo.ArgumentList.Add("uvicorn");
        startInfo.ArgumentList.Add("app.api.main:app");
        startInfo.ArgumentList.Add("--host");
        startInfo.ArgumentList.Add(_paths.Host);
        startInfo.ArgumentList.Add("--port");
        startInfo.ArgumentList.Add(_paths.Port.ToString());

        startInfo.Environment["PYTHONUNBUFFERED"] = "1";

        var process = Process.Start(startInfo)
            ?? throw new InvalidOperationException("启动 Python 后端进程失败。");

        _process = process;
        process.EnableRaisingEvents = true;
        process.OutputDataReceived += OnOutputDataReceived;
        process.ErrorDataReceived += OnOutputDataReceived;
        process.Exited += (_, _) => OutputReceived?.Invoke(this, $"Python 后端已退出，退出码：{process.ExitCode}。");
        process.BeginOutputReadLine();
        process.BeginErrorReadLine();

        OutputReceived?.Invoke(this, $"Python 后端已启动，进程 ID：{process.Id}。");
    }

    public async Task StopAsync()
    {
        if (_process is null)
        {
            return;
        }

        var process = _process;
        if (process.HasExited)
        {
            _process = null;
            return;
        }

        OutputReceived?.Invoke(this, "正在停止 Python 后端...");

        try
        {
            process.Kill(entireProcessTree: true);
            await process.WaitForExitAsync();
            OutputReceived?.Invoke(this, "Python 后端已停止。");
        }
        finally
        {
            _process = null;
        }
    }

    private void OnOutputDataReceived(object sender, DataReceivedEventArgs e)
    {
        if (!string.IsNullOrWhiteSpace(e.Data))
        {
            OutputReceived?.Invoke(this, e.Data);
        }
    }

    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }

        _disposed = true;
        if (_process is { HasExited: false })
        {
            _process.Kill(entireProcessTree: true);
        }

        _process?.Dispose();
    }
}
