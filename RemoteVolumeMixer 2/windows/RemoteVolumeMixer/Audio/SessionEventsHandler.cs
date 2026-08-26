using System;
using NAudio.CoreAudioApi;
using NAudio.CoreAudioApi.Interfaces;

namespace RemoteVolumeMixer.Audio;

/// <summary>
/// Riceve le notifiche push di Core Audio per una singola sessione.
/// I callback arrivano da un thread COM: qui non si fa mai lavoro pesante,
/// si inoltra e basta.
/// </summary>
internal sealed class SessionEventsHandler : IAudioSessionEventsHandler
{
    private readonly Action<float, bool> _onVolume;
    private readonly Action<AudioSessionState> _onState;
    private readonly Action<string> _onDisplayName;
    private readonly Action _onDisconnected;

    public SessionEventsHandler(
        Action<float, bool> onVolume,
        Action<AudioSessionState> onState,
        Action<string> onDisplayName,
        Action onDisconnected)
    {
        _onVolume = onVolume;
        _onState = onState;
        _onDisplayName = onDisplayName;
        _onDisconnected = onDisconnected;
    }

    public void OnVolumeChanged(float volume, bool isMuted) => Safe(() => _onVolume(volume, isMuted));

    public void OnDisplayNameChanged(string displayName) => Safe(() => _onDisplayName(displayName));

    public void OnIconPathChanged(string iconPath)
    {
        // L'icona viene risolta on-demand dall'IconProvider: nulla da fare qui.
    }

    public void OnChannelVolumeChanged(uint channelCount, IntPtr newVolumes, uint channelIndex)
    {
        // Il mixer lavora sul volume master della sessione, i canali singoli non servono.
    }

    public void OnGroupingParamChanged(ref Guid groupingId)
    {
        // Non utilizzato.
    }

    public void OnStateChanged(AudioSessionState state) => Safe(() => _onState(state));

    public void OnSessionDisconnected(AudioSessionDisconnectReason disconnectReason) => Safe(_onDisconnected);

    private static void Safe(Action action)
    {
        try
        {
            action();
        }
        catch (Exception ex)
        {
            Core.Logger.Debug("Session event handler error: " + ex.Message);
        }
    }
}
