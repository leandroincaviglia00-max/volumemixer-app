using System;
using System.Linq;
using System.Threading;
using System.Windows.Forms;
using RemoteVolumeMixer.App;
using RemoteVolumeMixer.Core;

namespace RemoteVolumeMixer;

internal static class Program
{
    [STAThread]
    private static void Main(string[] args)
    {
        using var singleInstance = new Mutex(true, @"Global\RemoteVolumeMixer.SingleInstance", out var isFirstInstance);
        if (!isFirstInstance)
        {
            // Una sola istanza: la seconda esce in silenzio.
            return;
        }

        var console = args.Any(a => string.Equals(a, "--console", StringComparison.OrdinalIgnoreCase));
        var verbose = args.Any(a => string.Equals(a, "--verbose", StringComparison.OrdinalIgnoreCase));

        var settings = AppSettings.Load();
        if (console)
        {
            Logger.EnsureConsole();
        }

        Logger.Initialize(verbose ? LogLevel.Debug : settings.ParsedLogLevel, console);

        MixerHost? host = null;
        try
        {
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);

            host = new MixerHost(settings);
            using var tray = new TrayApplicationContext(host, settings);

            host.Start();
            Application.Run(tray);
        }
        catch (Exception ex)
        {
            Logger.Error("Fatal error, shutting down", ex);
            MessageBox.Show(
                "Remote Volume Mixer could not start. Technical details are in the log file:" +
                Environment.NewLine + Environment.NewLine + Logger.CurrentLogFile,
                BuildInfo.ProductName,
                MessageBoxButtons.OK,
                MessageBoxIcon.Warning);
        }
        finally
        {
            if (host != null)
            {
                try
                {
                    host.StopAsync().GetAwaiter().GetResult();
                }
                catch (Exception ex)
                {
                    Logger.Debug("Shutdown error: " + ex.Message);
                }

                host.Dispose();
            }
        }
    }
}
