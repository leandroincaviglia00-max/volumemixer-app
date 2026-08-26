using System;
using System.IO;
using System.Text.Json;
using System.Text.Json.Serialization;
using RemoteVolumeMixer.Core;

namespace RemoteVolumeMixer.App;

/// <summary>
/// Impostazioni locali in %APPDATA%\RemoteVolumeMixer\settings.json.
/// Nessun account, nessuna credenziale, nessun dato sensibile.
/// </summary>
public sealed class AppSettings
{
    [JsonIgnore]
    public static string Directory { get; } = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "RemoteVolumeMixer");

    [JsonIgnore]
    public static string FilePath { get; } = Path.Combine(Directory, "settings.json");

    /// <summary>Percorso di adb.exe. null = ricerca automatica.</summary>
    [JsonPropertyName("adbPath")]
    public string? AdbPath { get; set; }

    /// <summary>Seriale del dispositivo da usare. null = primo telefono USB disponibile.</summary>
    [JsonPropertyName("deviceSerial")]
    public string? DeviceSerial { get; set; }

    [JsonPropertyName("devicePollIntervalMs")]
    public int DevicePollIntervalMs { get; set; } = 1500;

    [JsonPropertyName("audioPollIntervalMs")]
    public int AudioPollIntervalMs { get; set; } = 1200;

    [JsonPropertyName("includeMasterVolume")]
    public bool IncludeMasterVolume { get; set; } = true;

    /// <summary>Se true il PC prova ad aprire l'app Android quando rileva il telefono.</summary>
    [JsonPropertyName("autoLaunchAndroidApp")]
    public bool AutoLaunchAndroidApp { get; set; }

    [JsonPropertyName("logLevel")]
    public string LogLevel { get; set; } = "Info";

    [JsonIgnore]
    public LogLevel ParsedLogLevel =>
        Enum.TryParse<LogLevel>(LogLevel, true, out var level) ? level : Core.LogLevel.Info;

    public static AppSettings Load()
    {
        try
        {
            if (File.Exists(FilePath))
            {
                var json = File.ReadAllText(FilePath);
                var settings = JsonSerializer.Deserialize<AppSettings>(json);
                if (settings != null)
                {
                    settings.Normalize();
                    return settings;
                }
            }
        }
        catch (Exception ex)
        {
            Logger.Warn("settings.json could not be read, using defaults", ex);
        }

        var defaults = new AppSettings();
        defaults.Save();
        return defaults;
    }

    public void Save()
    {
        try
        {
            System.IO.Directory.CreateDirectory(Directory);
            var json = JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true });
            File.WriteAllText(FilePath, json);
        }
        catch (Exception ex)
        {
            Logger.Warn("Unable to save settings.json", ex);
        }
    }

    private void Normalize()
    {
        DevicePollIntervalMs = Math.Clamp(DevicePollIntervalMs, 500, 10000);
        AudioPollIntervalMs = Math.Clamp(AudioPollIntervalMs, 250, 10000);
    }
}
