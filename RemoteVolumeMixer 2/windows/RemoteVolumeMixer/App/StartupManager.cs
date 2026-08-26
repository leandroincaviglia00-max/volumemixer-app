using System;
using Microsoft.Win32;
using RemoteVolumeMixer.Core;

namespace RemoteVolumeMixer.App;

/// <summary>
/// Avvio automatico opzionale con Windows (HKCU\...\Run, nessun privilegio admin).
/// </summary>
public static class StartupManager
{
    private const string RunKeyPath = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string ValueName = "RemoteVolumeMixer";

    public static bool IsEnabled()
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(RunKeyPath, false);
            return key?.GetValue(ValueName) != null;
        }
        catch (Exception ex)
        {
            Logger.Debug("Startup check failed: " + ex.Message);
            return false;
        }
    }

    public static bool SetEnabled(bool enabled)
    {
        try
        {
            using var key = Registry.CurrentUser.CreateSubKey(RunKeyPath, true);
            if (key == null)
            {
                return false;
            }

            if (enabled)
            {
                var executable = Environment.ProcessPath;
                if (string.IsNullOrWhiteSpace(executable))
                {
                    return false;
                }

                key.SetValue(ValueName, "\"" + executable + "\"");
                Logger.Info("Start with Windows enabled");
            }
            else
            {
                key.DeleteValue(ValueName, false);
                Logger.Info("Start with Windows disabled");
            }

            return true;
        }
        catch (Exception ex)
        {
            Logger.Warn("Unable to change the startup entry", ex);
            return false;
        }
    }
}
