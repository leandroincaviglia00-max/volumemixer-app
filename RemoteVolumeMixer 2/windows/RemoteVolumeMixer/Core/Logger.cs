using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text;

namespace RemoteVolumeMixer.Core;

public enum LogLevel
{
    Debug = 0,
    Info = 1,
    Warn = 2,
    Error = 3
}

/// <summary>
/// Logging strutturato, thread-safe, senza dipendenze esterne.
/// Scrive su file (sempre) e su console (solo se avviato con --console).
/// Gli errori tecnici finiscono qui: all'utente non viene mai mostrato uno stack trace.
/// </summary>
public static class Logger
{
    private static readonly object Gate = new();
    private static string? _logFile;
    private static bool _console;

    public static LogLevel MinimumLevel { get; set; } = LogLevel.Info;

    public static string LogDirectory { get; } = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "RemoteVolumeMixer",
        "logs");

    public static string? CurrentLogFile
    {
        get { lock (Gate) { return _logFile; } }
    }

    public static void Initialize(LogLevel minimumLevel, bool console)
    {
        lock (Gate)
        {
            MinimumLevel = minimumLevel;
            _console = console;
            try
            {
                Directory.CreateDirectory(LogDirectory);
                _logFile = Path.Combine(LogDirectory, "rvm-" + DateTime.Now.ToString("yyyyMMdd") + ".log");
                PruneOldLogs();
            }
            catch
            {
                // Se non riusciamo a scrivere il log non dobbiamo mai far crashare l'app.
                _logFile = null;
            }
        }
    }

    public static void Debug(string message) => Write(LogLevel.Debug, message, null);

    public static void Info(string message) => Write(LogLevel.Info, message, null);

    public static void Warn(string message, Exception? ex = null) => Write(LogLevel.Warn, message, ex);

    public static void Error(string message, Exception? ex = null) => Write(LogLevel.Error, message, ex);

    private static void Write(LogLevel level, string message, Exception? ex)
    {
        if (level < MinimumLevel)
        {
            return;
        }

        var line = new StringBuilder()
            .Append(DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss.fff"))
            .Append(" [").Append(level.ToString().ToUpperInvariant()).Append("] ")
            .Append(message);

        if (ex != null)
        {
            line.Append(" | ").Append(ex.GetType().Name).Append(": ").Append(ex.Message);
            if (MinimumLevel == LogLevel.Debug && ex.StackTrace != null)
            {
                line.Append(Environment.NewLine).Append(ex.StackTrace);
            }
        }

        var text = line.ToString();
        lock (Gate)
        {
            if (_console)
            {
                Console.WriteLine(text);
            }

            System.Diagnostics.Trace.WriteLine(text);

            if (_logFile == null)
            {
                return;
            }

            try
            {
                File.AppendAllText(_logFile, text + Environment.NewLine, Encoding.UTF8);
            }
            catch
            {
                // ignorato di proposito
            }
        }
    }

    private static void PruneOldLogs()
    {
        try
        {
            var files = new DirectoryInfo(LogDirectory)
                .GetFiles("rvm-*.log")
                .OrderByDescending(f => f.LastWriteTimeUtc)
                .Skip(7)
                .ToList();

            foreach (var file in files)
            {
                try { file.Delete(); } catch { /* ignorato */ }
            }
        }
        catch
        {
            // ignorato
        }
    }

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool AllocConsole();

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool AttachConsole(int processId);

    /// <summary>Aggancia (o crea) una console quando l'utente avvia l'exe con --console.</summary>
    public static void EnsureConsole()
    {
        try
        {
            if (!AttachConsole(-1))
            {
                AllocConsole();
            }
        }
        catch
        {
            // ignorato
        }
    }
}
