using System;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;

namespace RemoteVolumeMixer.Audio;

/// <summary>
/// Utility per ricavare nome ed eseguibile di un processo in modo affidabile,
/// anche quando Process.MainModule non e' accessibile (processi protetti,
/// bitness diversa, permessi mancanti).
/// </summary>
internal static class ProcessInfo
{
    private const int ProcessQueryLimitedInformation = 0x1000;

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr OpenProcess(int desiredAccess, bool inheritHandle, int processId);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool CloseHandle(IntPtr handle);

    [DllImport("kernel32.dll", EntryPoint = "QueryFullProcessImageNameW", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool QueryFullProcessImageName(IntPtr handle, int flags, StringBuilder buffer, ref int size);

    public static string? GetExecutablePath(int pid)
    {
        if (pid <= 0)
        {
            return null;
        }

        var handle = IntPtr.Zero;
        try
        {
            handle = OpenProcess(ProcessQueryLimitedInformation, false, pid);
            if (handle != IntPtr.Zero)
            {
                var buffer = new StringBuilder(1024);
                var size = buffer.Capacity;
                if (QueryFullProcessImageName(handle, 0, buffer, ref size))
                {
                    return buffer.ToString(0, size);
                }
            }
        }
        catch (Exception ex)
        {
            Core.Logger.Debug("QueryFullProcessImageName failed for pid " + pid + ": " + ex.Message);
        }
        finally
        {
            if (handle != IntPtr.Zero)
            {
                CloseHandle(handle);
            }
        }

        // Fallback: puo' fallire su processi protetti, e' accettabile.
        try
        {
            using var process = Process.GetProcessById(pid);
            return process.MainModule?.FileName;
        }
        catch
        {
            return null;
        }
    }

    public static string GetProcessName(int pid)
    {
        try
        {
            using var process = Process.GetProcessById(pid);
            return process.ProcessName;
        }
        catch
        {
            return string.Empty;
        }
    }

    /// <summary>Nome "umano" dell'applicazione: FileDescription, product name o nome file.</summary>
    public static string GetFriendlyName(string? executablePath, string processName)
    {
        if (!string.IsNullOrWhiteSpace(executablePath) && File.Exists(executablePath))
        {
            try
            {
                var info = FileVersionInfo.GetVersionInfo(executablePath);
                if (!string.IsNullOrWhiteSpace(info.FileDescription))
                {
                    return info.FileDescription!.Trim();
                }

                if (!string.IsNullOrWhiteSpace(info.ProductName))
                {
                    return info.ProductName!.Trim();
                }
            }
            catch (Exception ex)
            {
                Core.Logger.Debug("FileVersionInfo failed for " + executablePath + ": " + ex.Message);
            }
        }

        if (!string.IsNullOrWhiteSpace(processName))
        {
            return Capitalize(processName);
        }

        if (!string.IsNullOrWhiteSpace(executablePath))
        {
            return Capitalize(Path.GetFileNameWithoutExtension(executablePath));
        }

        return "Unknown application";
    }

    private static string Capitalize(string value)
    {
        if (string.IsNullOrEmpty(value))
        {
            return value;
        }

        return char.ToUpperInvariant(value[0]) + value.Substring(1);
    }
}
