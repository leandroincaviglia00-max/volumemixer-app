namespace RemoteVolumeMixer.Protocol;

/// <summary>
/// Messaggio Android -> PC, deliberatamente "flat" e tollerante:
/// campi sconosciuti vengono ignorati, campi mancanti restano null.
/// </summary>
public sealed class InboundMessage
{
    public string Type { get; init; } = string.Empty;

    public int? ProtocolVersion { get; init; }

    public string? SessionId { get; init; }

    public int? Volume { get; init; }

    public bool? Muted { get; init; }

    public long? RequestId { get; init; }

    public string? IconKey { get; init; }

    public long? Nonce { get; init; }

    public string? Client { get; init; }
}

public static class InboundTypes
{
    public const string ClientHello = "client_hello";
    public const string SetVolume = "set_volume";
    public const string SetMute = "set_mute";
    public const string RequestSnapshot = "request_snapshot";
    public const string RequestIcon = "request_icon";
    public const string Ping = "ping";
}
