using System;

namespace RemoteVolumeMixer.Models;

public static class SessionStates
{
    public const string Active = "active";
    public const string Inactive = "inactive";
    public const string Expired = "expired";
}

/// <summary>
/// Rappresentazione trasporto-agnostica di una sessione audio di Windows.
/// </summary>
public sealed class AudioApplication
{
    public string SessionId { get; init; } = string.Empty;

    public string Name { get; set; } = string.Empty;

    public string ProcessName { get; init; } = string.Empty;

    public int Pid { get; init; }

    /// <summary>Volume 0-100 (individuale, non il master di Windows).</summary>
    public int Volume { get; set; }

    public bool Muted { get; set; }

    /// <summary>active | inactive | expired</summary>
    public string State { get; set; } = SessionStates.Inactive;

    public bool IsSystemSounds { get; init; }

    /// <summary>true solo per la card opzionale del dispositivo di output.</summary>
    public bool IsMaster { get; init; }

    /// <summary>
    /// Chiave stabile dell'icona: piu' sessioni dello stesso eseguibile condividono la
    /// stessa chiave, cosi' l'icona viaggia sul cavo USB una sola volta.
    /// Stringa vuota = nessuna icona disponibile (Android disegna il fallback).
    /// </summary>
    public string IconKey { get; init; } = string.Empty;

    public AudioApplication Clone() => (AudioApplication)MemberwiseClone();

    public override string ToString() =>
        Name + " (pid " + Pid.ToString() + ", " + Volume.ToString() + "%" + (Muted ? ", muted" : string.Empty) + ")";
}
