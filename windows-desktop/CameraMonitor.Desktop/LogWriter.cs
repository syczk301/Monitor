namespace CameraMonitor.Desktop;

internal static class LogWriter
{
    public static void Append(AppPaths paths, string message)
    {
        Directory.CreateDirectory(paths.DataDirectory);
        File.AppendAllText(
            paths.DesktopLogFile,
            $"{DateTimeOffset.Now:yyyy-MM-dd HH:mm:ss zzz} {message}{Environment.NewLine}");
    }
}
