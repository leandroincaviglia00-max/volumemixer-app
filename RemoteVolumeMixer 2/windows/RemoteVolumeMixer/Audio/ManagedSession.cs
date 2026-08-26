using NAudio.CoreAudioApi;
using RemoteVolumeMixer.Models;

namespace RemoteVolumeMixer.Audio;

/// <summary>Sessione audio tracciata: wrapper COM + ultimo stato conosciuto.</summary>
internal sealed class ManagedSession
{
    public ManagedSession(string sessionId, AudioSessionControl control, AudioApplication application)
    {
        SessionId = sessionId;
        Control = control;
        Application = application;
    }

    public string SessionId { get; }

    public AudioSessionControl Control { get; }

    public AudioApplication Application { get; }

    public SessionEventsHandler? Events { get; set; }

    public bool EventsRegistered { get; set; }
}
