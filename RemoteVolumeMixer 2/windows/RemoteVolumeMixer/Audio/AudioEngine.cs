using System;
using System.Collections.Generic;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using NAudio.CoreAudioApi;
using RemoteVolumeMixer.Core;
using RemoteVolumeMixer.Models;

namespace RemoteVolumeMixer.Audio;

/// <summary>
/// Accesso reale alle sessioni audio di Windows tramite Core Audio
/// (MMDevice + Audio Session API + ISimpleAudioVolume).
/// Nessun dato simulato: ogni valore letto o scritto passa da WASAPI.
/// </summary>
public sealed class AudioEngine : IDisposable
{
    public const string MasterSessionId = "__master__";

    private readonly object _gate = new();
    private readonly Dictionary<string, ManagedSession> _sessions = new(StringComparer.Ordinal);
    private readonly IconProvider _icons;
    private readonly bool _includeMaster;

    private MMDeviceEnumerator? _enumerator;
    private MMDevice? _device;
    private string _deviceId = string.Empty;
    private AudioApplication? _master;
    private AudioEndpointVolumeNotificationDelegate? _masterHandler;
    private bool _disposed;

    public AudioEngine(IconProvider icons, bool includeMaster)
    {
        _icons = icons;
        _includeMaster = includeMaster;
    }

    /// <summary>Nuova applicazione audio rilevata.</summary>
    public event Action<AudioApplication>? ApplicationAdded;

    /// <summary>Applicazione scomparsa (processo chiuso o sessione scaduta).</summary>
    public event Action<string>? ApplicationRemoved;

    /// <summary>Cambio di nome o di stato attivo/inattivo.</summary>
    public event Action<AudioApplication>? ApplicationUpdated;

    /// <summary>Volume o mute cambiati (anche quando la modifica arriva da Windows).</summary>
    public event Action<AudioApplication>? VolumeChanged;

    /// <summary>Richiesta di riconciliazione immediata (arriva dai callback COM).</summary>
    public event Action? ReconcileRequested;

    public int Count
    {
        get
        {
            lock (_gate)
            {
                return _sessions.Count + (_master != null ? 1 : 0);
            }
        }
    }

    public void Initialize()
    {
        EnsureDevice();
        Reconcile();
    }

    public List<AudioApplication> Snapshot()
    {
        lock (_gate)
        {
            var result = new List<AudioApplication>(_sessions.Count + 1);
            if (_master != null)
            {
                result.Add(_master.Clone());
            }

            result.AddRange(_sessions.Values.Select(s => s.Application.Clone()));
            return result;
        }
    }

    /// <summary>
    /// Allinea la mappa interna alla realta': aggiunge le sessioni nuove, rimuove
    /// quelle scadute e rileva i cambi di volume/mute effettuati da Windows.
    /// E' anche la rete di sicurezza se un callback COM viene perso.
    /// </summary>
    public void Reconcile()
    {
        if (_disposed)
        {
            return;
        }

        EnsureDevice();
        var device = _device;
        if (device == null)
        {
            return;
        }

        SessionCollection sessions;
        try
        {
            var manager = device.AudioSessionManager;
            manager.RefreshSessions();
            sessions = manager.Sessions;
        }
        catch (Exception ex)
        {
            Logger.Warn("Unable to enumerate audio sessions", ex);
            return;
        }

        var seen = new HashSet<string>(StringComparer.Ordinal);
        var count = 0;
        try
        {
            count = sessions.Count;
        }
        catch (Exception ex)
        {
            Logger.Debug("Session count failed: " + ex.Message);
        }

        for (var i = 0; i < count; i++)
        {
            AudioSessionControl? control = null;
            try
            {
                control = sessions[i];
                Track(control, seen, out var keepWrapper);
                if (!keepWrapper)
                {
                    control.Dispose();
                }
            }
            catch (Exception ex)
            {
                Logger.Debug("Skipping unreadable audio session: " + ex.Message);
                try { control?.Dispose(); } catch { /* ignorato */ }
            }
        }

        List<string> stale;
        lock (_gate)
        {
            stale = _sessions.Keys.Where(k => !seen.Contains(k)).ToList();
        }

        foreach (var id in stale)
        {
            Remove(id, "session closed");
        }

        RefreshMaster();
    }

    public bool SetVolume(string sessionId, int volume, out string? error)
    {
        error = null;
        volume = Math.Clamp(volume, 0, 100);
        var scalar = volume / 100f;

        if (sessionId == MasterSessionId)
        {
            var device = _device;
            if (device == null || _master == null)
            {
                error = "output device unavailable";
                return false;
            }

            try
            {
                var previous = _master.Volume;
                device.AudioEndpointVolume.MasterVolumeLevelScalar = scalar;
                _master.Volume = volume;
                Logger.Info("Windows Output volume changed: " + previous + " -> " + volume);
                return true;
            }
            catch (Exception ex)
            {
                Logger.Warn("Failed to set master volume", ex);
                error = "unable to set output volume";
                return false;
            }
        }

        ManagedSession? session;
        lock (_gate)
        {
            _sessions.TryGetValue(sessionId, out session);
        }

        if (session == null)
        {
            error = "session not found";
            return false;
        }

        try
        {
            var previous = session.Application.Volume;
            session.Control.SimpleAudioVolume.Volume = scalar;
            session.Application.Volume = volume;
            if (previous != volume)
            {
                Logger.Info(session.Application.Name + " volume changed: " + previous + " -> " + volume);
            }

            return true;
        }
        catch (Exception ex)
        {
            Logger.Warn("Failed to set volume for " + session.Application.Name, ex);
            Remove(sessionId, "session no longer responding");
            error = "session no longer available";
            return false;
        }
    }

    public bool SetMute(string sessionId, bool muted, out string? error)
    {
        error = null;

        if (sessionId == MasterSessionId)
        {
            var device = _device;
            if (device == null || _master == null)
            {
                error = "output device unavailable";
                return false;
            }

            try
            {
                device.AudioEndpointVolume.Mute = muted;
                _master.Muted = muted;
                Logger.Info("Windows Output " + (muted ? "muted" : "unmuted"));
                return true;
            }
            catch (Exception ex)
            {
                Logger.Warn("Failed to set master mute", ex);
                error = "unable to change mute state";
                return false;
            }
        }

        ManagedSession? session;
        lock (_gate)
        {
            _sessions.TryGetValue(sessionId, out session);
        }

        if (session == null)
        {
            error = "session not found";
            return false;
        }

        try
        {
            session.Control.SimpleAudioVolume.Mute = muted;
            session.Application.Muted = muted;
            Logger.Info(session.Application.Name + " " + (muted ? "muted" : "unmuted"));
            return true;
        }
        catch (Exception ex)
        {
            Logger.Warn("Failed to change mute for " + session.Application.Name, ex);
            Remove(sessionId, "session no longer responding");
            error = "session no longer available";
            return false;
        }
    }

    // ---------------------------------------------------------------- interni

    private void EnsureDevice()
    {
        try
        {
            _enumerator ??= new MMDeviceEnumerator();
            var current = _enumerator.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
            if (_device != null && string.Equals(current.ID, _deviceId, StringComparison.Ordinal))
            {
                try { current.Dispose(); } catch { /* ignorato */ }
                return;
            }

            Logger.Info("Default audio output: " + current.FriendlyName);
            DetachDevice();

            _device = current;
            _deviceId = current.ID;

            try
            {
                var manager = current.AudioSessionManager;
                manager.OnSessionCreated += OnSessionCreated;
            }
            catch (Exception ex)
            {
                Logger.Debug("Session notifications unavailable, falling back to polling: " + ex.Message);
            }

            AttachMaster();
        }
        catch (Exception ex)
        {
            if (_device != null)
            {
                Logger.Warn("Audio output device lost", ex);
                DetachDevice();
            }
            else
            {
                Logger.Debug("No usable audio output device: " + ex.Message);
            }
        }
    }

    private void OnSessionCreated(object sender, NAudio.CoreAudioApi.Interfaces.IAudioSessionControl newSession)
    {
        // Il callback arriva da un thread COM: non toccare COM qui, chiedi solo
        // una riconciliazione al monitor.
        Logger.Debug("Core Audio reported a new session");
        try
        {
            ReconcileRequested?.Invoke();
        }
        catch (Exception ex)
        {
            Logger.Debug("Reconcile request failed: " + ex.Message);
        }
    }

    private void Track(AudioSessionControl control, HashSet<string> seen, out bool keepWrapper)
    {
        keepWrapper = false;

        AudioSessionState state;
        try
        {
            state = control.State;
        }
        catch
        {
            return;
        }

        if (state == AudioSessionState.AudioSessionStateExpired)
        {
            // La sessione appartiene a un processo terminato: la lasciamo cadere.
            return;
        }

        var instanceId = ReadInstanceId(control);
        if (string.IsNullOrEmpty(instanceId))
        {
            return;
        }

        var sessionId = ShortHash(instanceId);
        seen.Add(sessionId);

        ManagedSession? existing;
        lock (_gate)
        {
            _sessions.TryGetValue(sessionId, out existing);
        }

        if (existing != null)
        {
            SyncExisting(existing, control, state);
            return;
        }

        var isSystemSounds = TryRead(() => control.IsSystemSoundsSession, false);
        var pid = (int)TryRead(() => control.GetProcessID, 0u);
        var processName = isSystemSounds ? "System" : ProcessInfo.GetProcessName(pid);
        var exePath = isSystemSounds ? null : ProcessInfo.GetExecutablePath(pid);
        var displayName = TryRead(() => control.DisplayName, string.Empty) ?? string.Empty;
        var name = isSystemSounds
            ? "System Sounds"
            : (!string.IsNullOrWhiteSpace(displayName) && !displayName.StartsWith("@", StringComparison.Ordinal)
                ? displayName.Trim()
                : ProcessInfo.GetFriendlyName(exePath, processName));

        var iconPath = TryRead(() => control.IconPath, string.Empty);
        var application = new AudioApplication
        {
            SessionId = sessionId,
            Name = name,
            ProcessName = processName,
            Pid = pid,
            Volume = ReadVolume(control),
            Muted = ReadMute(control),
            State = MapState(state),
            IsSystemSounds = isSystemSounds,
            IsMaster = false,
            IconKey = _icons.RegisterSource(exePath, iconPath, isSystemSounds, false)
        };

        var managed = new ManagedSession(sessionId, control, application);
        managed.Events = new SessionEventsHandler(
            (volume, muted) => OnSessionVolume(sessionId, volume, muted),
            newState => OnSessionState(sessionId, newState),
            newName => OnSessionName(sessionId, newName),
            () => Remove(sessionId, "session disconnected"));

        try
        {
            control.RegisterEventClient(managed.Events);
            managed.EventsRegistered = true;
        }
        catch (Exception ex)
        {
            Logger.Debug("Push notifications unavailable for " + name + ", polling will cover it: " + ex.Message);
        }

        lock (_gate)
        {
            _sessions[sessionId] = managed;
        }

        keepWrapper = true;
        Logger.Info("Audio session detected: " + application);
        Raise(ApplicationAdded, application.Clone());
    }

    private void SyncExisting(ManagedSession managed, AudioSessionControl duplicate, AudioSessionState state)
    {
        var app = managed.Application;
        var volume = ReadVolume(managed.Control, app.Volume);
        var muted = ReadMute(managed.Control, app.Muted);
        var mappedState = MapState(state);

        var volumeChanged = volume != app.Volume || muted != app.Muted;
        var stateChanged = !string.Equals(mappedState, app.State, StringComparison.Ordinal);

        if (volumeChanged)
        {
            Logger.Debug(app.Name + " external change: " + app.Volume + " -> " + volume + (muted ? " (muted)" : string.Empty));
            app.Volume = volume;
            app.Muted = muted;
        }

        if (stateChanged)
        {
            app.State = mappedState;
        }

        if (volumeChanged)
        {
            Raise(VolumeChanged, app.Clone());
        }

        if (stateChanged)
        {
            Raise(ApplicationUpdated, app.Clone());
        }
    }

    private void AttachMaster()
    {
        if (!_includeMaster || _device == null)
        {
            return;
        }

        try
        {
            var endpoint = _device.AudioEndpointVolume;
            _master = new AudioApplication
            {
                SessionId = MasterSessionId,
                Name = "Windows Output",
                ProcessName = _device.FriendlyName,
                Pid = 0,
                Volume = (int)Math.Round(endpoint.MasterVolumeLevelScalar * 100f),
                Muted = endpoint.Mute,
                State = SessionStates.Active,
                IsSystemSounds = false,
                IsMaster = true,
                IconKey = IconProvider.MasterIconKey
            };

            _masterHandler = data =>
            {
                try
                {
                    if (_master == null)
                    {
                        return;
                    }

                    var volume = (int)Math.Round(data.MasterVolume * 100f);
                    if (volume == _master.Volume && data.Muted == _master.Muted)
                    {
                        return;
                    }

                    _master.Volume = volume;
                    _master.Muted = data.Muted;
                    Raise(VolumeChanged, _master.Clone());
                }
                catch (Exception ex)
                {
                    Logger.Debug("Master volume notification failed: " + ex.Message);
                }
            };

            endpoint.OnVolumeNotification += _masterHandler;
        }
        catch (Exception ex)
        {
            Logger.Debug("Master volume unavailable: " + ex.Message);
            _master = null;
        }
    }

    private void RefreshMaster()
    {
        if (_master == null || _device == null)
        {
            return;
        }

        try
        {
            var endpoint = _device.AudioEndpointVolume;
            var volume = (int)Math.Round(endpoint.MasterVolumeLevelScalar * 100f);
            var muted = endpoint.Mute;
            if (volume == _master.Volume && muted == _master.Muted)
            {
                return;
            }

            _master.Volume = volume;
            _master.Muted = muted;
            Raise(VolumeChanged, _master.Clone());
        }
        catch (Exception ex)
        {
            Logger.Debug("Master refresh failed: " + ex.Message);
        }
    }

    private void OnSessionVolume(string sessionId, float volume, bool muted)
    {
        AudioApplication? snapshot = null;
        lock (_gate)
        {
            if (!_sessions.TryGetValue(sessionId, out var managed))
            {
                return;
            }

            var scaled = (int)Math.Round(volume * 100f);
            if (scaled == managed.Application.Volume && muted == managed.Application.Muted)
            {
                return;
            }

            Logger.Info(managed.Application.Name + " volume changed: " + managed.Application.Volume + " -> " + scaled +
                        (muted != managed.Application.Muted ? (muted ? " (muted)" : " (unmuted)") : string.Empty));
            managed.Application.Volume = scaled;
            managed.Application.Muted = muted;
            snapshot = managed.Application.Clone();
        }

        Raise(VolumeChanged, snapshot);
    }

    private void OnSessionState(string sessionId, AudioSessionState state)
    {
        if (state == AudioSessionState.AudioSessionStateExpired)
        {
            Remove(sessionId, "session expired");
            return;
        }

        AudioApplication? snapshot = null;
        lock (_gate)
        {
            if (!_sessions.TryGetValue(sessionId, out var managed))
            {
                return;
            }

            var mapped = MapState(state);
            if (string.Equals(mapped, managed.Application.State, StringComparison.Ordinal))
            {
                return;
            }

            managed.Application.State = mapped;
            snapshot = managed.Application.Clone();
        }

        Raise(ApplicationUpdated, snapshot);
    }

    private void OnSessionName(string sessionId, string displayName)
    {
        if (string.IsNullOrWhiteSpace(displayName) || displayName.StartsWith("@", StringComparison.Ordinal))
        {
            return;
        }

        AudioApplication? snapshot = null;
        lock (_gate)
        {
            if (!_sessions.TryGetValue(sessionId, out var managed))
            {
                return;
            }

            if (string.Equals(managed.Application.Name, displayName.Trim(), StringComparison.Ordinal))
            {
                return;
            }

            managed.Application.Name = displayName.Trim();
            snapshot = managed.Application.Clone();
        }

        Raise(ApplicationUpdated, snapshot);
    }

    private void Remove(string sessionId, string reason)
    {
        ManagedSession? managed;
        lock (_gate)
        {
            if (!_sessions.Remove(sessionId, out managed) || managed == null)
            {
                return;
            }
        }

        Logger.Info("Audio session gone: " + managed.Application.Name + " (" + reason + ")");
        DisposeSession(managed);
        try
        {
            ApplicationRemoved?.Invoke(sessionId);
        }
        catch (Exception ex)
        {
            Logger.Debug("ApplicationRemoved handler failed: " + ex.Message);
        }
    }

    private static void DisposeSession(ManagedSession managed)
    {
        try
        {
            if (managed.EventsRegistered && managed.Events != null)
            {
                managed.Control.UnRegisterEventClient(managed.Events);
            }
        }
        catch (Exception ex)
        {
            Core.Logger.Debug("UnRegisterEventClient failed: " + ex.Message);
        }

        try
        {
            managed.Control.Dispose();
        }
        catch
        {
            // ignorato
        }
    }

    private void DetachDevice()
    {
        List<ManagedSession> sessions;
        lock (_gate)
        {
            sessions = _sessions.Values.ToList();
            _sessions.Clear();
        }

        foreach (var session in sessions)
        {
            DisposeSession(session);
            try
            {
                ApplicationRemoved?.Invoke(session.SessionId);
            }
            catch
            {
                // ignorato
            }
        }

        if (_device != null)
        {
            try
            {
                if (_masterHandler != null)
                {
                    _device.AudioEndpointVolume.OnVolumeNotification -= _masterHandler;
                }
            }
            catch
            {
                // ignorato
            }

            try
            {
                _device.AudioSessionManager.OnSessionCreated -= OnSessionCreated;
            }
            catch
            {
                // ignorato
            }

            try
            {
                _device.Dispose();
            }
            catch
            {
                // ignorato
            }
        }

        if (_master != null)
        {
            var id = _master.SessionId;
            _master = null;
            try
            {
                ApplicationRemoved?.Invoke(id);
            }
            catch
            {
                // ignorato
            }
        }

        _masterHandler = null;
        _device = null;
        _deviceId = string.Empty;
    }

    private void Raise(Action<AudioApplication>? handler, AudioApplication? application)
    {
        if (handler == null || application == null)
        {
            return;
        }

        try
        {
            handler(application);
        }
        catch (Exception ex)
        {
            Logger.Debug("Audio event handler failed: " + ex.Message);
        }
    }

    private static string ReadInstanceId(AudioSessionControl control)
    {
        var instanceId = TryRead(() => control.GetSessionInstanceIdentifier, string.Empty);
        if (!string.IsNullOrWhiteSpace(instanceId))
        {
            return instanceId!;
        }

        var identifier = TryRead(() => control.GetSessionIdentifier, string.Empty);
        var pid = TryRead(() => control.GetProcessID, 0u);
        if (!string.IsNullOrWhiteSpace(identifier))
        {
            return identifier + "|" + pid;
        }

        return pid == 0 ? string.Empty : "pid:" + pid;
    }

    private static int ReadVolume(AudioSessionControl control, int fallback = 0)
    {
        try
        {
            return (int)Math.Round(control.SimpleAudioVolume.Volume * 100f);
        }
        catch
        {
            return fallback;
        }
    }

    private static bool ReadMute(AudioSessionControl control, bool fallback = false)
    {
        try
        {
            return control.SimpleAudioVolume.Mute;
        }
        catch
        {
            return fallback;
        }
    }

    private static T TryRead<T>(Func<T> reader, T fallback)
    {
        try
        {
            return reader();
        }
        catch
        {
            return fallback;
        }
    }

    private static string MapState(AudioSessionState state) => state switch
    {
        AudioSessionState.AudioSessionStateActive => SessionStates.Active,
        AudioSessionState.AudioSessionStateInactive => SessionStates.Inactive,
        _ => SessionStates.Expired
    };

    private static string ShortHash(string value)
    {
        var bytes = SHA256.HashData(Encoding.UTF8.GetBytes(value));
        var builder = new StringBuilder(20);
        for (var i = 0; i < 10; i++)
        {
            builder.Append(bytes[i].ToString("x2"));
        }

        return builder.ToString();
    }

    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }

        _disposed = true;
        DetachDevice();

        try
        {
            _enumerator?.Dispose();
        }
        catch
        {
            // ignorato
        }

        _enumerator = null;
    }
}
