namespace CameraMonitor.Desktop;

internal sealed class AppPaths
{
    private AppPaths(string repositoryRoot, string pythonExecutable, string host, int port)
    {
        RepositoryRoot = repositoryRoot;
        PythonExecutable = pythonExecutable;
        Host = host;
        Port = port;
    }

    public string RepositoryRoot { get; }

    public string PythonExecutable { get; }

    public string Host { get; }

    public int Port { get; }

    public string DashboardUrl => $"http://localhost:{Port}";

    public string EnvFile => Path.Combine(RepositoryRoot, ".env");

    public string DataDirectory => Path.Combine(RepositoryRoot, "data");

    public string DesktopLogFile => Path.Combine(DataDirectory, "desktop.log");

    public string TrayLogFile => Path.Combine(DataDirectory, "tray.log");

    public static AppPaths Discover()
    {
        var root = FindRepositoryRoot();
        var python = FindPythonExecutable(root);
        var host = Environment.GetEnvironmentVariable("CAMERA_MONITOR_HOST");
        var portText = Environment.GetEnvironmentVariable("CAMERA_MONITOR_PORT");

        return new AppPaths(
            root,
            python,
            string.IsNullOrWhiteSpace(host) ? "0.0.0.0" : host,
            int.TryParse(portText, out var port) && port > 0 ? port : 8000);
    }

    private static string FindRepositoryRoot()
    {
        var explicitRoot = Environment.GetEnvironmentVariable("CAMERA_MONITOR_ROOT");
        if (IsRepositoryRoot(explicitRoot))
        {
            return Path.GetFullPath(explicitRoot!);
        }

        var candidates = new List<string>
        {
            Environment.CurrentDirectory,
            AppContext.BaseDirectory,
        };

        var current = new DirectoryInfo(AppContext.BaseDirectory);
        for (var i = 0; i < 8 && current is not null; i++)
        {
            candidates.Add(current.FullName);
            current = current.Parent;
        }

        foreach (var candidate in candidates.Distinct(StringComparer.OrdinalIgnoreCase))
        {
            if (IsRepositoryRoot(candidate))
            {
                return Path.GetFullPath(candidate);
            }
        }

        throw new DirectoryNotFoundException(
            "找不到项目根目录。请把 CAMERA_MONITOR_ROOT 设置为包含 pyproject.toml 和 app\\api\\main.py 的目录。");
    }

    private static bool IsRepositoryRoot(string? path)
    {
        if (string.IsNullOrWhiteSpace(path))
        {
            return false;
        }

        return File.Exists(Path.Combine(path, "pyproject.toml"))
            && File.Exists(Path.Combine(path, "app", "api", "main.py"));
    }

    private static string FindPythonExecutable(string repositoryRoot)
    {
        var explicitPython = Environment.GetEnvironmentVariable("CAMERA_MONITOR_PYTHON");
        if (File.Exists(explicitPython))
        {
            return Path.GetFullPath(explicitPython!);
        }

        var userProfile = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        var candidates = new[]
        {
            Path.Combine(repositoryRoot, ".venv", "Scripts", "python.exe"),
            Path.Combine(userProfile, ".conda", "envs", "DL", "python.exe"),
            "python",
        };

        foreach (var candidate in candidates)
        {
            if (candidate.Equals("python", StringComparison.OrdinalIgnoreCase) || File.Exists(candidate))
            {
                return candidate;
            }
        }

        return "python";
    }
}
