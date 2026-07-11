using Microsoft.Win32;

namespace CameraMonitor.Desktop;

internal static class WindowsStartup
{
    private const string RegistryPath = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string ValueName = "智能监控";
    private const string LegacyValueName = "Camera Monitor";

    public static bool IsEnabled()
    {
        using var key = Registry.CurrentUser.OpenSubKey(RegistryPath, writable: false);
        return IsCurrentExecutable(key?.GetValue(ValueName) as string)
            || IsCurrentExecutable(key?.GetValue(LegacyValueName) as string);
    }

    public static void SetEnabled(bool enabled)
    {
        using var key = Registry.CurrentUser.OpenSubKey(RegistryPath, writable: true)
            ?? Registry.CurrentUser.CreateSubKey(RegistryPath, writable: true);

        if (enabled)
        {
            key.SetValue(ValueName, $"\"{Application.ExecutablePath}\" --minimized");
            key.DeleteValue(LegacyValueName, throwOnMissingValue: false);
        }
        else
        {
            key.DeleteValue(ValueName, throwOnMissingValue: false);
            key.DeleteValue(LegacyValueName, throwOnMissingValue: false);
        }
    }

    private static bool IsCurrentExecutable(string? command)
    {
        return command?.Contains(Application.ExecutablePath, StringComparison.OrdinalIgnoreCase) == true;
    }
}
