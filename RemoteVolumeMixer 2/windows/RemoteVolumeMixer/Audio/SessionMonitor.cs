using System;
using System.Threading;
using System.Threading.Tasks;
using RemoteVolumeMixer.Core;

namespace RemoteVolumeMixer.Audio;

/// <summary>
/// Tiene aggiornato l'AudioEngine: riconciliazione periodica leggera
/// (nessun polling aggressivo) piu' risveglio immediato quando Core Audio
/// segnala una sessione nuova. Cosi' aprire Spotify a mixer avviato lo fa
/// comparire subito sul telefono.
/// </summary>
public sealed class SessionMonitor : IDisposable
{
    private readonly AudioEngine _engine;
    private readonly int _intervalMs;
    private readonly SemaphoreSlim _wake = new(0, 1);
    private CancellationTokenSource? _cts;
    private Task? _loop;

    public SessionMonitor(AudioEngine engine, int intervalMs)
    {
        _engine = engine;
        _intervalMs = Math.Clamp(intervalMs, 250, 10000);
        _engine.ReconcileRequested += Wake;
    }

    public void Start()
    {
        if (_loop != null)
        {
            return;
        }

        _cts = new CancellationTokenSource();
        var token = _cts.Token;
        _loop = Task.Run(() => LoopAsync(token), CancellationToken.None);
        Logger.Info("Audio session monitor started (interval " + _intervalMs + " ms)");
    }

    public void Wake()
    {
        try
        {
            if (_wake.CurrentCount == 0)
            {
                _wake.Release();
            }
        }
        catch (SemaphoreFullException)
        {
            // gia' sveglio
        }
        catch (ObjectDisposedException)
        {
            // in chiusura
        }
    }

    private async Task LoopAsync(CancellationToken token)
    {
        while (!token.IsCancellationRequested)
        {
            try
            {
                await _wake.WaitAsync(_intervalMs, token).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                return;
            }
            catch (ObjectDisposedException)
            {
                return;
            }

            if (token.IsCancellationRequested)
            {
                return;
            }

            try
            {
                _engine.Reconcile();
            }
            catch (Exception ex)
            {
                Logger.Warn("Audio reconcile failed", ex);
            }
        }
    }

    public void Dispose()
    {
        _engine.ReconcileRequested -= Wake;
        try
        {
            _cts?.Cancel();
        }
        catch
        {
            // ignorato
        }

        try
        {
            _loop?.Wait(1500);
        }
        catch
        {
            // ignorato
        }

        _cts?.Dispose();
        _wake.Dispose();
        _loop = null;
    }
}
