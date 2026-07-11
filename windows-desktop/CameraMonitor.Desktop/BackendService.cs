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
        TryAttachExistingProcess();
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

        if (TryAttachExistingProcess())
        {
            OutputReceived?.Invoke(this, $"已接管现有 Python 后端，进程 ID：{_process!.Id}。");
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
        File.WriteAllText(_paths.BackendPidFile, process.Id.ToString());
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
            DeletePidFile();
        }
    }

    private bool TryAttachExistingProcess()
    {
        if (!File.Exists(_paths.BackendPidFile)
            || !int.TryParse(File.ReadAllText(_paths.BackendPidFile).Trim(), out var pid))
        {
            return false;
        }

        try
        {
            var process = Process.GetProcessById(pid);
            if (process.HasExited
                || !process.ProcessName.StartsWith("python", StringComparison.OrdinalIgnoreCase))
            {
                DeletePidFile();
                return false;
            }

            _process = process;
            process.EnableRaisingEvents = true;
            process.Exited += (_, _) =>
            {
                if (_process?.Id == pid)
                {
                    _process = null;
                    DeletePidFile();
                }
                OutputReceived?.Invoke(this, $"Python 后端已退出，进程 ID：{pid}。");
            };
            return true;
        }
        catch
        {
            DeletePidFile();
            return false;
        }
    }

    private void DeletePidFile()
    {
        try
        {
            File.Delete(_paths.BackendPidFile);
        }
        catch
        {
            // A stale PID file will be revalidated on the next start.
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
