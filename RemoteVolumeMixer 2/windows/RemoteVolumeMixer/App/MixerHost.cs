using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using RemoteVolumeMixer.Audio;
using RemoteVolumeMixer.Core;
using RemoteVolumeMixer.Models;
using RemoteVolumeMixer.Protocol;
using RemoteVolumeMixer.Transport;

namespace RemoteVolumeMixer.App;

/// <summary>
/// Collega le due meta' del programma: le sessioni audio reali di Windows
/// e il canale USB verso il telefono. Tutta la logica di protocollo vive qui.
/// </summary>
public sealed class MixerHost : IDisposable
{
    private readonly AppSettings _settings;
    private readonly IconProvider _icons = new();
    private readonly AudioEngine _audio;
    private readonly SessionMonitor _monitor;
    private readonly UsbTransport _transport;
    private bool _disposed;

    public MixerHost(AppSettings settings)
    {
        _settings = settings;
        _audio = new AudioEngine(_icons, settings.IncludeMasterVolume);
        _monitor = new SessionMonitor(_audio, settings.AudioPollIntervalMs);
        _transport = new UsbTransport(settings);

        _audio.ApplicationAdded += app => _transport.Send(new ApplicationAddedMessage { Application = ApplicationDto.From(app) });
        _audio.ApplicationUpdated += app => _transport.Send(new ApplicationUpdatedMessage { Application = ApplicationDto.From(app) });
        _audio.ApplicationRemoved += id => _transport.Send(new ApplicationRemovedMessage { SessionId = id });
        _audio.VolumeChanged += app => _transport.Send(new VolumeChangedMessage
        {
            SessionId = app.SessionId,
            Volume = app.Volume,
            Muted = app.Muted
        });

        _transport.ClientReady += OnClientReady;
        _transport.ClientLost += () => StatusChanged?.Invoke();
        _transport.StatusChanged += () => StatusChanged?.Invoke();
        _transport.MessageReceived += OnMessageReceived;
    }

    public event Action? StatusChanged;

    public bool Connected => _transport.IsConnected;

    public string TransportStatusText => _transport.StatusText;

    public int ApplicationCount => _audio.Count;

    public void Start()
    {
        Logger.Info(BuildInfo.ProductName + " started (v" + BuildInfo.Version + ")");
        _audio.Initialize();
        _monitor.Start();
        _transport.Start();
    }

    public async Task StopAsync()
    {
        await _transport.StopAsync().ConfigureAwait(false);
        _monitor.Dispose();
        _audio.Dispose();
        Logger.Info(BuildInfo.ProductName + " stopped");
    }

    public void Reconnect() => _transport.RequestReconnect();

    private void OnClientReady()
    {
        // Riconciliazione immediata: il telefono riceve la fotografia piu' fresca possibile.
        _monitor.Wake();
        SendSnapshot();
        StatusChanged?.Invoke();
    }

    private void SendSnapshot()
    {
        var applications = Sort(_audio.Snapshot());
        _transport.Send(new SnapshotMessage
        {
            Applications = applications.Select(ApplicationDto.From).ToList()
        });

        Logger.Info("Snapshot sent: " + applications.Count + " application(s)");
    }

    private static List<AudioApplication> Sort(List<AudioApplication> applications) =>
        applications
            .OrderByDescending(a => a.IsMaster)
            .ThenByDescending(a => string.Equals(a.State, SessionStates.Active, StringComparison.Ordinal))
            .ThenBy(a => a.IsSystemSounds)
            .ThenBy(a => a.Name, StringComparer.CurrentCultureIgnoreCase)
            .ToList();

    private void OnMessageReceived(InboundMessage message)
    {
        switch (message.Type)
        {
            case InboundTypes.SetVolume:
                HandleSetVolume(message);
                break;

            case InboundTypes.SetMute:
                HandleSetMute(message);
                break;

            case InboundTypes.RequestSnapshot:
                _monitor.Wake();
                SendSnapshot();
                break;

            case InboundTypes.RequestIcon:
                HandleIconRequest(message);
                break;

            default:
                Logger.Debug("Ignoring unsupported message type: " + message.Type);
                break;
        }
    }

    private void HandleSetVolume(InboundMessage message)
    {
        if (string.IsNullOrEmpty(message.SessionId) || message.Volume == null)
        {
            Reply(message, false, "malformed set_volume");
            return;
        }

        var ok = _audio.SetVolume(message.SessionId!, message.Volume.Value, out var error);
        Reply(message, ok, error);

        if (!ok)
        {
            return;
        }

        // Conferma con il valore realmente applicato: il telefono si allinea a Windows.
        var applied = _audio.Snapshot().FirstOrDefault(a =>
            string.Equals(a.SessionId, message.SessionId, StringComparison.Ordinal));

        if (applied != null)
        {
            _transport.Send(new VolumeChangedMessage
            {
                SessionId = applied.SessionId,
                Volume = applied.Volume,
                Muted = applied.Muted
            });
        }
    }

    private void HandleSetMute(InboundMessage message)
    {
        if (string.IsNullOrEmpty(message.SessionId) || message.Muted == null)
        {
            Reply(message, false, "malformed set_mute");
            return;
        }

        var ok = _audio.SetMute(message.SessionId!, message.Muted.Value, out var error);
        Reply(message, ok, error);

        if (!ok)
        {
            return;
        }

        var applied = _audio.Snapshot().FirstOrDefault(a =>
            string.Equals(a.SessionId, message.SessionId, StringComparison.Ordinal));

        if (applied != null)
        {
            _transport.Send(new VolumeChangedMessage
            {
                SessionId = applied.SessionId,
                Volume = applied.Volume,
                Muted = applied.Muted
            });
        }
    }

    private void HandleIconRequest(InboundMessage message)
    {
        var key = message.IconKey;
        if (string.IsNullOrEmpty(key))
        {
            return;
        }

        // L'estrazione dell'icona non deve mai rallentare il loop di lettura.
        Task.Run(() =>
        {
            string? png = null;
            try
            {
                png = _icons.GetIconBase64(key!);
            }
            catch (Exception ex)
            {
                Logger.Debug("Icon request failed for " + key + ": " + ex.Message);
            }

            _transport.Send(new IconMessage { IconKey = key!, Png = png });
        });
    }

    private void Reply(InboundMessage message, bool ok, string? error)
    {
        if (message.RequestId == null)
        {
            return;
        }

        _transport.Send(new AckMessage
        {
            RequestId = message.RequestId.Value,
            Ok = ok,
            Error = ok ? null : error
        });
    }

    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }

        _disposed = true;
        _transport.Dispose();
        _monitor.Dispose();
        _audio.Dispose();
    }
}
