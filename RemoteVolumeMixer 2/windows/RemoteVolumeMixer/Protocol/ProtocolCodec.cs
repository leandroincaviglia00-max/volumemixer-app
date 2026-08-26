using System;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace RemoteVolumeMixer.Protocol;

/// <summary>
/// Codec del protocollo: una riga = un messaggio JSON (NDJSON).
/// Semplice da leggere in un log, versionato, bidirezionale.
/// </summary>
public static class ProtocolCodec
{
    /// <summary>Versione corrente del protocollo.</summary>
    public const int Version = 1;

    /// <summary>Versione minima accettata da questo client.</summary>
    public const int MinSupportedVersion = 1;

    private static readonly JsonSerializerOptions WriteOptions = new()
    {
        DefaultIgnoreCondition = JsonIgnoreCondition.Never,
        WriteIndented = false
    };

    public static string Encode(OutboundMessage message) =>
        JsonSerializer.Serialize(message, message.GetType(), WriteOptions);

    /// <summary>
    /// Decodifica una riga. Ritorna null se la riga non e' un pacchetto valido:
    /// il chiamante logga e prosegue, senza mai chiudere la connessione per un
    /// singolo pacchetto malformato.
    /// </summary>
    public static InboundMessage? Decode(string line)
    {
        if (string.IsNullOrWhiteSpace(line))
        {
            return null;
        }

        try
        {
            using var doc = JsonDocument.Parse(line);
            var root = doc.RootElement;
            if (root.ValueKind != JsonValueKind.Object)
            {
                return null;
            }

            var type = GetString(root, "type");
            if (string.IsNullOrEmpty(type))
            {
                return null;
            }

            return new InboundMessage
            {
                Type = type!,
                ProtocolVersion = GetInt(root, "v"),
                SessionId = GetString(root, "sessionId"),
                Volume = GetInt(root, "volume"),
                Muted = GetBool(root, "muted"),
                RequestId = GetLong(root, "requestId"),
                IconKey = GetString(root, "iconKey"),
                Nonce = GetLong(root, "nonce"),
                Client = GetString(root, "client")
            };
        }
        catch (JsonException)
        {
            return null;
        }
        catch (Exception)
        {
            return null;
        }
    }

    private static string? GetString(JsonElement root, string name) =>
        root.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() : null;

    private static int? GetInt(JsonElement root, string name)
    {
        if (!root.TryGetProperty(name, out var v))
        {
            return null;
        }

        if (v.ValueKind == JsonValueKind.Number && v.TryGetInt32(out var i))
        {
            return i;
        }

        if (v.ValueKind == JsonValueKind.String && int.TryParse(v.GetString(), out var parsed))
        {
            return parsed;
        }

        return null;
    }

    private static long? GetLong(JsonElement root, string name)
    {
        if (!root.TryGetProperty(name, out var v))
        {
            return null;
        }

        if (v.ValueKind == JsonValueKind.Number && v.TryGetInt64(out var l))
        {
            return l;
        }

        if (v.ValueKind == JsonValueKind.String && long.TryParse(v.GetString(), out var parsed))
        {
            return parsed;
        }

        return null;
    }

    private static bool? GetBool(JsonElement root, string name)
    {
        if (!root.TryGetProperty(name, out var v))
        {
            return null;
        }

        return v.ValueKind switch
        {
            JsonValueKind.True => true,
            JsonValueKind.False => false,
            _ => null
        };
    }
}
