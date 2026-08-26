using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Reflection;
using System.Windows.Forms;
using RemoteVolumeMixer.Core;

namespace RemoteVolumeMixer.App;

/// <summary>
/// Piccola presenza nella tray: stato USB, numero di applicazioni,
/// avvio automatico, log e uscita. Nessuna GUI complessa su Windows.
/// </summary>
public sealed class TrayApplicationContext : ApplicationContext
{
    private readonly MixerHost _host;
    private readonly AppSettings _settings;
    private readonly NotifyIcon _icon;
    private readonly ToolStripMenuItem _statusItem;
    private readonly ToolStripMenuItem _applicationsItem;
    private readonly ToolStripMenuItem _startWithWindowsItem;
    private readonly System.Windows.Forms.Timer _refreshTimer;
    private bool _lastConnected;
    private bool _firstRefresh = true;

    public TrayApplicationContext(MixerHost host, AppSettings settings)
    {
        _host = host;
        _settings = settings;

        _statusItem = new ToolStripMenuItem("USB: starting...") { Enabled = false };
        _applicationsItem = new ToolStripMenuItem("Applications: 0") { Enabled = false };
        _startWithWindowsItem = new ToolStripMenuItem("Start with Windows")
        {
            CheckOnClick = true,
            Checked = StartupManager.IsEnabled()
        };
        _startWithWindowsItem.Click += OnStartWithWindowsClicked;

        var menu = new ContextMenuStrip();
        menu.Items.Add(new ToolStripMenuItem(BuildInfo.ProductName + "  v" + BuildInfo.Version) { Enabled = false });
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add(_statusItem);
        menu.Items.Add(_applicationsItem);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add(new ToolStripMenuItem("Reconnect USB", null, (_, _) => _host.Reconnect()));
        menu.Items.Add(_startWithWindowsItem);
        menu.Items.Add(new ToolStripMenuItem("Open log folder", null, (_, _) => OpenPath(Logger.LogDirectory)));
        menu.Items.Add(new ToolStripMenuItem("Open settings file", null, (_, _) => OpenSettings()));
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add(new ToolStripMenuItem("Exit", null, (_, _) => ExitApplication()));

        _icon = new NotifyIcon
        {
            Icon = LoadIcon(),
            Visible = true,
            Text = BuildInfo.ProductName,
            ContextMenuStrip = menu
        };

        _icon.DoubleClick += (_, _) => OpenPath(Logger.LogDirectory);

        _refreshTimer = new System.Windows.Forms.Timer { Interval = 1000 };
        _refreshTimer.Tick += (_, _) => Refresh();
        _refreshTimer.Start();
        Refresh();
    }

    private void Refresh()
    {
        var connected = _host.Connected;
        var status = _host.TransportStatusText;
        var count = _host.ApplicationCount;

        _statusItem.Text = status;
        _applicationsItem.Text = "Applications: " + count;

        var tooltip = BuildInfo.ProductName + Environment.NewLine + status + Environment.NewLine + "Applications: " + count;
        _icon.Text = tooltip.Length > 62 ? tooltip.Substring(0, 62) : tooltip;

        if (!_firstRefresh && connected != _lastConnected)
        {
            ShowBalloon(connected ? "Phone connected" : "Phone disconnected", status);
        }

        _lastConnected = connected;
        _firstRefresh = false;
    }

    private void ShowBalloon(string title, string text)
    {
        try
        {
            _icon.BalloonTipTitle = title;
            _icon.BalloonTipText = text;
            _icon.ShowBalloonTip(2500);
        }
        catch (Exception ex)
        {
            Logger.Debug("Balloon tip failed: " + ex.Message);
        }
    }

    private void OnStartWithWindowsClicked(object? sender, EventArgs e)
    {
        var desired = _startWithWindowsItem.Checked;
        if (!StartupManager.SetEnabled(desired))
        {
            _startWithWindowsItem.Checked = !desired;
            ShowBalloon(BuildInfo.ProductName, "Could not change the Windows startup setting.");
        }
    }

    private void OpenSettings()
    {
        _settings.Save();
        OpenPath(AppSettings.FilePath);
    }

    private static void OpenPath(string path)
    {
        try
        {
            Process.Start(new ProcessStartInfo
            {
                FileName = path,
                UseShellExecute = true
            });
        }
        catch (Exception ex)
        {
            Logger.Warn("Unable to open " + path, ex);
        }
    }

    private static Icon LoadIcon()
    {
        try
        {
            var stream = Assembly.GetExecutingAssembly().GetManifestResourceStream("RemoteVolumeMixer.Assets.app.ico");
            if (stream != null)
            {
                using (stream)
                {
                    return new Icon(stream);
                }
            }
        }
        catch (Exception ex)
        {
            Logger.Debug("Embedded tray icon unavailable: " + ex.Message);
        }

        return SystemIcons.Application;
    }

    private void ExitApplication()
    {
        Logger.Info("Exit requested from the tray menu");
        _refreshTimer.Stop();
        _icon.Visible = false;
        ExitThread();
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            _refreshTimer.Dispose();
            _icon.Visible = false;
            _icon.Dispose();
        }

        base.Dispose(disposing);
    }
}
