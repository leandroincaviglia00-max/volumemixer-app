using System;
using System.Collections.Generic;
using System.IO;
using RemoteVolumeMixer.Core;

namespace RemoteVolumeMixer.Transport;

/// <summary>
/// Trova adb.exe senza pretendere che l'utente abbia configurato il PATH.
/// Ordine: percorso esplicito nelle impostazioni, cartella accanto all'exe,
/// PATH, installazioni standard dell'Android SDK.
/// </summary>
public static class AdbLocator
{
    public static string? Locate(string? configuredPath)
    {
        foreach (var candidate in Candidates(configuredPath))
        {
            try
            {
                if (!string.IsNullOrWhiteSpace(candidate) && File.Exists(candidate))
                {
                    return Path.GetFullPath(candidate);
                }
            }
            catch
            {
                // percorso non valido, si prosegue
            }
        }

        return null;
    }

    private static IEnumerable<string> Candidates(string? configuredPath)
    {
        if (!string.IsNullOrWhiteSpace(configuredPath))
        {
            yield return configuredPath!;
        }

        // adb distribuito accanto all'exe (installazione "portable").
        yield return Path.Combine(AppContext.BaseDirectory, "adb", "adb.exe");
        yield return Path.Combine(AppContext.BaseDirectory, "platform-tools", "adb.exe");
        yield return Path.Combine(AppContext.BaseDirectory, "adb.exe");

        foreach (var fromPath in FromEnvironmentPath())
        {
            yield return fromPath;
        }

        var localAppData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        var userProfile = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        var programFiles = Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles);
        var programFilesX86 = Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86);

        foreach (var variable in new[] { "ANDROID_HOME", "ANDROID_SDK_ROOT" })
        {
            var root = Environment.GetEnvironmentVariable(variable);
            if (!string.IsNullOrWhiteSpace(root))
            {
                yield return Path.Combine(root!, "platform-tools", "adb.exe");
            }
        }

        yield return Path.Combine(localAppData, "Android", "Sdk", "platform-tools", "adb.exe");
        yield return Path.Combine(userProfile, "AppData", "Local", "Android", "Sdk", "platform-tools", "adb.exe");
        yield return Path.Combine(programFiles, "Android", "android-sdk", "platform-tools", "adb.exe");
        yield return Path.Combine(programFilesX86, "Android", "android-sdk", "platform-tools", "adb.exe");
        yield return Path.Combine(localAppData, "Programs", "scrcpy", "adb.exe");
        yield return @"C:\platform-tools\adb.exe";
    }

    private static IEnumerable<string> FromEnvironmentPath()
    {
        var path = Environment.GetEnvironmentVariable("PATH");
        if (string.IsNullOrWhiteSpace(path))
        {
            yield break;
        }

        foreach (var entry in path!.Split(';', StringSplitOptions.RemoveEmptyEntries))
        {
            string candidate;
            try
            {
                candidate = Path.Combine(entry.Trim().Trim('"'), "adb.exe");
            }
            catch
            {
                continue;
            }

            yield return candidate;
        }
    }

    public static void LogMissing()
    {
        Logger.Warn("adb.exe not found. Install Android platform-tools, or drop adb.exe into an 'adb' folder next to RemoteVolumeMixer.exe, or set \"adbPath\" in settings.json");
    }
}
