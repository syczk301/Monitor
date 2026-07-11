namespace CameraMonitor.Desktop;

internal static class Program
{
    [STAThread]
    private static void Main(string[] args)
    {
        ApplicationConfiguration.Initialize();
        using var context = new MonitorApplicationContext(args);
        Application.Run(context);
    }
}
