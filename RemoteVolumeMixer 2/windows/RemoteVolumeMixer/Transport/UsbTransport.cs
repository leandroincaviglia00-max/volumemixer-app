using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using System.Threading.Channels;
using System.Threading.Tasks;
using RemoteVolumeMixer.App;
using RemoteVolumeMixer.Core;
using RemoteVolumeMixer.Protocol;

namespace RemoteVolumeMixer.Transport;

public enum TransportStatus
{
    AdbMissing,
    NoDevice,
    Unauthorized,
    WaitingForApp,
    Connected
}

/// <summary>
/// Canale USB verso il telefono.
///
/// Come funziona davvero: l'app Android pubblica un socket unix nel namespace
/// astratto di Linux; adb lo espone al PC su una porta di loopback tramite il
/// cavo USB ("adb forward ... localabstract:"). Il PC e' il client.
/// Niente Wi-Fi, niente LAN, niente IP del telefono, niente server web:
/// il traffico esiste solo dentro il cavo e dentro il loopback locale.
/// </summary>
public sealed class UsbTransport : IDisposable
{
    public const string AbstractSocketName = "remotevolumemixer";
    private const string AndroidComponent = "com.remotevolumemixer/.MainActivity";
    private const int InboundTimeoutMs = 12000;

    private readonly AppSettings _settings;
    private readonly object _gate = new();

    private CancellationTokenSource? _cts;
    private Task? _loop;
    private Channel<string>? _outbound;
    private volatile bool _handshakeDone;
    private volatile TransportStatus _status = TransportStatus.NoDevice;
    private volatile string _deviceLabel = string.Empty;
    private volatile string _clientLabel = string.Empty;
    private long _lastInboundTicks;
    private bool _adbMissingLogged;
    private bool _waitingForAppLogged;

    public UsbTransport(AppSettings settings)
    {
        _settings = settings;
    }

    public event Action<InboundMessage>? MessageReceived;

    /// <summary>Handshake completato: il telefono e' pronto a ricevere lo snapshot.</summary>
    public event Action? ClientReady;

    public event Action? ClientLost;

    public event Action? StatusChanged;

    public bool IsConnected => _status == TransportStatus.Connected;

    public TransportStatus Status => _status;

    public string DeviceLabel => _deviceLabel;

    public string StatusText => _status switch
    {
        TransportStatus.AdbMissing => "USB: adb not found",
        TransportStatus.NoDevice => "USB: no phone connected",
        TransportStatus.Unauthorized => "USB: authorize this PC on the phone",
        TransportStatus.WaitingForApp => "USB: phone detected, waiting for the app",
        TransportStatus.Connected => "USB: connected" + (string.IsNullOrEmpty(_deviceLabel) ? string.Empty : " (" + _deviceLabel + ")"),
        _ => "USB: unknown"
    };

    public void Start()
    {
        if (_loop != null)
        {
            return;
        }

        _cts = new CancellationTokenSource();
        var token = _cts.Token;
        _loop = Task.Run(() => RunAsync(token), CancellationToken.None);
        Logger.Info("USB transport initialized");
    }

    public async Task StopAsync()
    {
        try
        {
            _cts?.Cancel();
        }
        catch
        {
            // ignorato
        }

        if (_loop != null)
        {
            try
            {
                await Task.WhenAny(_loop, Task.Delay(2500)).ConfigureAwait(false);
            }
            catch
            {
                // ignorato
            }
        }

        _loop = null;
        Logger.Info("USB transport stopped");
    }

    /// <summary>
    /// Accoda un messaggio. Se il telefono non e' collegato il messaggio viene
    /// scartato in silenzio: alla riconnessione parte comunque uno snapshot completo.
    /// </summary>
    public void Send(OutboundMessage message)
    {
        var channel = _outbound;
        if (channel == null || !_handshakeDone)
        {
            return;
        }

        try
        {
            channel.Writer.TryWrite(ProtocolCodec.Encode(message));
        }
        catch (Exception ex)
        {
            Logger.Debug("Failed to enqueue " + message.Type + ": " + ex.Message);
        }
    }

    /// <summary>Forza una riconnessione (voce "Reconnect" nel menu della tray).</summary>
    public void RequestReconnect()
    {
        Logger.Info("Manual reconnect requested");
        CloseCurrentSession();
    }

    private volatile TcpClient? _client;

    private void CloseCurrentSession()
    {
        var client = _client;
        if (client == null)
        {
            return;
        }

        try
        {
            client.Close();
        }
        catch
        {
            // ignorato
        }
    }

    private async Task RunAsync(CancellationToken token)
    {
        while (!token.IsCancellationRequested)
        {
            var adbPath = AdbLocator.Locate(_settings.AdbPath);
            if (adbPath == null)
            {
                if (!_adbMissingLogged)
                {
                    AdbLocator.LogMissing();
                    _adbMissingLogged = true;
                }

                SetStatus(TransportStatus.AdbMissing, string.Empty);
                await DelayAsync(4000, token).ConfigureAwait(false);
                continue;
            }

            _adbMissingLogged = false;
            var adb = new AdbClient(adbPath);
            await adb.StartServerAsync(token).ConfigureAwait(false);

            while (!token.IsCancellationRequested)
            {
                var devices = await adb.ListDevicesAsync(token).ConfigureAwait(false);
                var target = SelectDevice(devices);

                if (target == null)
                {
                    var unauthorized = devices.Any(d =>
                        string.Equals(d.State, "unauthorized", StringComparison.OrdinalIgnoreCase));

                    if (unauthorized)
                    {
                        SetStatus(TransportStatus.Unauthorized, string.Empty);
                        Logger.Warn("Phone connected but not authorized: accept the 'Allow USB debugging' prompt on the device");
                    }
                    else
                    {
                        SetStatus(TransportStatus.NoDevice, string.Empty);
                    }

                    _waitingForAppLogged = false;
                    await DelayAsync(_settings.DevicePollIntervalMs, token).ConfigureAwait(false);
                    continue;
                }

                await RunSessionAsync(adb, target, token).ConfigureAwait(false);
                await DelayAsync(_settings.DevicePollIntervalMs, token).ConfigureAwait(false);
            }
        }
    }

    private AdbDevice? SelectDevice(List<AdbDevice> devices)
    {
        var usable = devices.Where(d => d.IsUsable).ToList();
        if (usable.Count == 0)
        {
            return null;
        }

        if (!string.IsNullOrWhiteSpace(_settings.DeviceSerial))
        {
            return usable.FirstOrDefault(d =>
                string.Equals(d.Serial, _settings.DeviceSerial, StringComparison.OrdinalIgnoreCase));
        }

        return usable[0];
    }

    private async Task RunSessionAsync(AdbClient adb, AdbDevice device, CancellationToken token)
    {
        var port = await adb.ForwardAsync(device.Serial, AbstractSocketName, token).ConfigureAwait(false);
        if (port == 0)
        {
            SetStatus(TransportStatus.WaitingForApp, device.Label);
            return;
        }

        if (_settings.AutoLaunchAndroidApp)
        {
            await adb.LaunchAppAsync(device.Serial, AndroidComponent, token).ConfigureAwait(false);
        }

        TcpClient? client = null;
        try
        {
            client = new TcpClient();
            client.NoDelay = true;

            using var connectTimeout = CancellationTokenSource.CreateLinkedTokenSource(token);
            connectTimeout.CancelAfter(2500);
            await client.ConnectAsync("127.0.0.1", port, connectTimeout.Token).ConfigureAwait(false);
        }
        catch (Exception ex)
        {
            client?.Dispose();
            await adb.RemoveForwardAsync(device.Serial, port, CancellationToken.None).ConfigureAwait(false);

            // Caso normalissimo: telefono collegato ma app Android non in primo piano.
            SetStatus(TransportStatus.WaitingForApp, device.Label);
            if (!_waitingForAppLogged)
            {
                Logger.Info("Phone " + device.Label + " is connected, waiting for the Remote Volume Mixer app to open (" + ex.GetType().Name + ")");
                _waitingForAppLogged = true;
            }

            return;
        }

        if (client == null)
        {
            return;
        }

        _waitingForAppLogged = false;
        _client = client;
        var channel = Channel.CreateUnbounded<string>(new UnboundedChannelOptions
        {
            SingleReader = true,
            SingleWriter = false
        });

        _outbound = channel;
        _handshakeDone = false;
        Interlocked.Exchange(ref _lastInboundTicks, DateTime.UtcNow.Ticks);

        using var sessionCts = CancellationTokenSource.CreateLinkedTokenSource(token);
        var sessionToken = sessionCts.Token;

        Logger.Info("Android device connected: " + device.Label + " (serial " + device.Serial + ", local port " + port + ")");

        try
        {
            await using var stream = client.GetStream();
            using var reader = new StreamReader(stream, new UTF8Encoding(false), false, 16 * 1024);
            await using var writer = new StreamWriter(stream, new UTF8Encoding(false), 16 * 1024) { AutoFlush = false, NewLine = "\n" };

            // Handshake: il PC si presenta per primo.
            await writer.WriteLineAsync(ProtocolCodec.Encode(new HelloMessage
            {
                Host = SafeHostName(),
                AppVersion = BuildInfo.Version
            })).ConfigureAwait(false);
            await writer.FlushAsync().ConfigureAwait(false);

            var writeTask = WriteLoopAsync(channel, writer, sessionToken);
            var readTask = ReadLoopAsync(reader, writer, sessionCts, sessionToken);
            var watchdogTask = WatchdogAsync(sessionCts, sessionToken);

            await Task.WhenAny(readTask, writeTask).ConfigureAwait(false);
            sessionCts.Cancel();
            await Task.WhenAll(SafeTask(readTask), SafeTask(writeTask), SafeTask(watchdogTask)).ConfigureAwait(false);
        }
        catch (Exception ex)
        {
            Logger.Debug("USB session ended: " + ex.Message);
        }
        finally
        {
            var wasConnected = _handshakeDone;
            _handshakeDone = false;
            _outbound = null;
            _client = null;
            channel.Writer.TryComplete();

            try { client.Dispose(); } catch { /* ignorato */ }

            await adb.RemoveForwardAsync(device.Serial, port, CancellationToken.None).ConfigureAwait(false);

            if (wasConnected)
            {
                Logger.Warn("USB transport disconnected (" + device.Label + ")");
                SetStatus(TransportStatus.WaitingForApp, device.Label);
                try { ClientLost?.Invoke(); } catch { /* ignorato */ }
            }
            else
            {
                SetStatus(TransportStatus.WaitingForApp, device.Label);
            }
        }
    }

    private async Task ReadLoopAsync(
        StreamReader reader,
        StreamWriter writer,
        CancellationTokenSource sessionCts,
        CancellationToken token)
    {
        try
        {
            while (!token.IsCancellationRequested)
            {
                var line = await reader.ReadLineAsync(token).ConfigureAwait(false);
                if (line == null)
                {
                    return;
                }

                Interlocked.Exchange(ref _lastInboundTicks, DateTime.UtcNow.Ticks);

                var message = ProtocolCodec.Decode(line);
                if (message == null)
                {
                    Logger.Warn("Invalid packet ignored (" + Math.Min(line.Length, 120) + " bytes)");
                    continue;
                }

                if (!_handshakeDone)
                {
                    if (!await CompleteHandshakeAsync(message, writer).ConfigureAwait(false))
                    {
                        sessionCts.Cancel();
                        return;
                    }

                    continue;
                }

                if (message.Type == InboundTypes.Ping)
                {
                    Send(new PongMessage { Nonce = message.Nonce ?? 0 });
                    continue;
                }

                try
                {
                    MessageReceived?.Invoke(message);
                }
                catch (Exception ex)
                {
                    Logger.Warn("Handler failed for " + message.Type, ex);
                }
            }
        }
        catch (OperationCanceledException)
        {
            // chiusura normale
        }
        catch (Exception ex)
        {
            Logger.Debug("Read loop ended: " + ex.Message);
        }
    }

    private async Task<bool> CompleteHandshakeAsync(InboundMessage message, StreamWriter writer)
    {
        if (message.Type != InboundTypes.ClientHello)
        {
            Logger.Warn("Unexpected first packet '" + message.Type + "', waiting for client_hello");
            return true;
        }

        var version = message.ProtocolVersion ?? 0;
        if (version < ProtocolCodec.MinSupportedVersion || version > ProtocolCodec.Version)
        {
            Logger.Error("Protocol version mismatch: phone speaks v" + version + ", this build speaks v" + ProtocolCodec.Version);
            try
            {
                await writer.WriteLineAsync(ProtocolCodec.Encode(new ErrorMessage
                {
                    Code = "protocol_version",
                    Message = "Protocol v" + version + " is not supported. Update the Windows client or the Android app."
                })).ConfigureAwait(false);
                await writer.FlushAsync().ConfigureAwait(false);
            }
            catch
            {
                // ignorato
            }

            return false;
        }

        _clientLabel = message.Client ?? string.Empty;
        _handshakeDone = true;
        SetStatus(TransportStatus.Connected, _deviceLabel);
        Logger.Info("Handshake complete with " + (string.IsNullOrEmpty(_clientLabel) ? "Android device" : _clientLabel) + " (protocol v" + version + ")");

        try
        {
            ClientReady?.Invoke();
        }
        catch (Exception ex)
        {
            Logger.Warn("ClientReady handler failed", ex);
        }

        return true;
    }

    private static async Task WriteLoopAsync(Channel<string> channel, StreamWriter writer, CancellationToken token)
    {
        try
        {
            await foreach (var line in channel.Reader.ReadAllAsync(token).ConfigureAwait(false))
            {
                await writer.WriteLineAsync(line).ConfigureAwait(false);
                await writer.FlushAsync().ConfigureAwait(false);
            }
        }
        catch (OperationCanceledException)
        {
            // chiusura normale
        }
        catch (Exception ex)
        {
            Logger.Debug("Write loop ended: " + ex.Message);
        }
    }

    private async Task WatchdogAsync(CancellationTokenSource sessionCts, CancellationToken token)
    {
        try
        {
            while (!token.IsCancellationRequested)
            {
                await Task.Delay(2000, token).ConfigureAwait(false);
                var last = new DateTime(Interlocked.Read(ref _lastInboundTicks), DateTimeKind.Utc);
                if ((DateTime.UtcNow - last).TotalMilliseconds > InboundTimeoutMs)
                {
                    Logger.Warn("No data from the phone for " + (InboundTimeoutMs / 1000) + "s, dropping the USB session");
                    sessionCts.Cancel();
                    CloseCurrentSession();
                    return;
                }
            }
        }
        catch (OperationCanceledException)
        {
            // normale
        }
    }

    private static Task SafeTask(Task task) => task.ContinueWith(_ => { }, TaskScheduler.Default);

    private void SetStatus(TransportStatus status, string deviceLabel)
    {
        var changed = _status != status || !string.Equals(_deviceLabel, deviceLabel, StringComparison.Ordinal);
        _status = status;
        _deviceLabel = deviceLabel;
        if (!changed)
        {
            return;
        }

        Logger.Debug("Transport status: " + StatusText);
        try
        {
            StatusChanged?.Invoke();
        }
        catch
        {
            // ignorato
        }
    }

    private static async Task DelayAsync(int milliseconds, CancellationToken token)
    {
        try
        {
            await Task.Delay(Math.Max(250, milliseconds), token).ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
            // normale
        }
    }

    private static string SafeHostName()
    {
        try
        {
            return Environment.MachineName;
        }
        catch
        {
            return "Windows PC";
        }
    }

    public void Dispose()
    {
        try
        {
            _cts?.Cancel();
        }
        catch
        {
            // ignorato
        }

        CloseCurrentSession();
        _cts?.Dispose();
    }
}
