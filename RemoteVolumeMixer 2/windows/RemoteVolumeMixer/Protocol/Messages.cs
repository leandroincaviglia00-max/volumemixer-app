using System.Collections.Generic;
using System.Text.Json.Serialization;
using RemoteVolumeMixer.Models;

namespace RemoteVolumeMixer.Protocol;

/// <summary>
/// Messaggi PC -> Android. Il campo "type" e' una proprieta' calcolata:
/// System.Text.Json serializza le proprieta' in sola lettura, quindi ogni
/// messaggio porta sempre il proprio discriminatore senza polimorfismo magico.
/// </summary>
public abstract class OutboundMessage
{
    [JsonPropertyName("type")]
    public abstract string Type { get; }

    [JsonPropertyName("v")]
    public int ProtocolVersion => ProtocolCodec.Version;
}

public sealed class HelloMessage : OutboundMessage
{
    [JsonPropertyName("type")]
    public override string Type => "hello";

    [JsonPropertyName("host")]
    public string Host { get; init; } = string.Empty;

    [JsonPropertyName("appVersion")]
    public string AppVersion { get; init; } = string.Empty;

    [JsonPropertyName("minProtocolVersion")]
    public int MinProtocolVersion => ProtocolCodec.MinSupportedVersion;
}

public sealed class SnapshotMessage : OutboundMessage
{
    [JsonPropertyName("type")]
    public override string Type => "snapshot";

    [JsonPropertyName("applications")]
    public List<ApplicationDto> Applications { get; init; } = new();
}

public sealed class ApplicationAddedMessage : OutboundMessage
{
    [JsonPropertyName("type")]
    public override string Type => "app_added";

    [JsonPropertyName("application")]
    public ApplicationDto Application { get; init; } = new();
}

public sealed class ApplicationUpdatedMessage : OutboundMessage
{
    [JsonPropertyName("type")]
    public override string Type => "app_updated";

    [JsonPropertyName("application")]
    public ApplicationDto Application { get; init; } = new();
}

public sealed class ApplicationRemovedMessage : OutboundMessage
{
    [JsonPropertyName("type")]
    public override string Type => "app_removed";

    [JsonPropertyName("sessionId")]
    public string SessionId { get; init; } = string.Empty;
}

public sealed class VolumeChangedMessage : OutboundMessage
{
    [JsonPropertyName("type")]
    public override string Type => "volume_changed";

    [JsonPropertyName("sessionId")]
    public string SessionId { get; init; } = string.Empty;

    [JsonPropertyName("volume")]
    public int Volume { get; init; }

    [JsonPropertyName("muted")]
    public bool Muted { get; init; }
}

public sealed class IconMessage : OutboundMessage
{
    [JsonPropertyName("type")]
    public override string Type => "icon";

    [JsonPropertyName("iconKey")]
    public string IconKey { get; init; } = string.Empty;

    /// <summary>PNG codificato base64, oppure null se non disponibile.</summary>
    [JsonPropertyName("png")]
    public string? Png { get; init; }
}

public sealed class AckMessage : OutboundMessage
{
    [JsonPropertyName("type")]
    public override string Type => "ack";

    [JsonPropertyName("requestId")]
    public long RequestId { get; init; }

    [JsonPropertyName("ok")]
    public bool Ok { get; init; }

    [JsonPropertyName("error")]
    public string? Error { get; init; }
}

public sealed class PongMessage : OutboundMessage
{
    [JsonPropertyName("type")]
    public override string Type => "pong";

    [JsonPropertyName("nonce")]
    public long Nonce { get; init; }
}

public sealed class ErrorMessage : OutboundMessage
{
    [JsonPropertyName("type")]
    public override string Type => "error";

    [JsonPropertyName("code")]
    public string Code { get; init; } = string.Empty;

    [JsonPropertyName("message")]
    public string Message { get; init; } = string.Empty;
}

/// <summary>Payload di una singola applicazione audio.</summary>
public sealed class ApplicationDto
{
    [JsonPropertyName("sessionId")]
    public string SessionId { get; init; } = string.Empty;

    [JsonPropertyName("name")]
    public string Name { get; init; } = string.Empty;

    [JsonPropertyName("processName")]
    public string ProcessName { get; init; } = string.Empty;

    [JsonPropertyName("pid")]
    public int Pid { get; init; }

    [JsonPropertyName("volume")]
    public int Volume { get; init; }

    [JsonPropertyName("muted")]
    public bool Muted { get; init; }

    [JsonPropertyName("state")]
    public string State { get; init; } = SessionStates.Inactive;

    [JsonPropertyName("isSystemSounds")]
    public bool IsSystemSounds { get; init; }

    [JsonPropertyName("isMaster")]
    public bool IsMaster { get; init; }

    [JsonPropertyName("iconKey")]
    public string IconKey { get; init; } = string.Empty;

    public static ApplicationDto From(AudioApplication app) => new()
    {
        SessionId = app.SessionId,
        Name = app.Name,
        ProcessName = app.ProcessName,
        Pid = app.Pid,
        Volume = app.Volume,
        Muted = app.Muted,
        State = app.State,
        IsSystemSounds = app.IsSystemSounds,
        IsMaster = app.IsMaster,
        IconKey = app.IconKey
    };
}
