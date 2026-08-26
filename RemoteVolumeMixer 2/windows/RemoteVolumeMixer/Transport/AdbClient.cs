using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using RemoteVolumeMixer.Core;

namespace RemoteVolumeMixer.Transport;

public sealed record AdbResult(int ExitCode, string StandardOutput, string StandardError)
{
    public bool Success => ExitCode == 0;
}

public sealed record AdbDevice(string Serial, string State, string Model)
{
    public bool IsUsable => string.Equals(State, "device", StringComparison.OrdinalIgnoreCase);

    public string Label => string.IsNullOrWhiteSpace(Model) ? Serial : Model.Replace('_', ' ');
}

/// <summary>
/// Wrapper minimale sul binario adb: elenco dispositivi, port forwarding e
/// avvio dell'app. Nessuna dipendenza esterna, nessun socket di rete verso
/// il telefono: tutto passa dal cavo USB gestito da adb.
/// </summary>
public sealed class AdbClient
{
    private readonly string _adbPath;

    public AdbClient(string adbPath)
    {
        _adbPath = adbPath;
    }

    public string AdbPath => _adbPath;

    public async Task<AdbResult> RunAsync(string arguments, int timeoutMs, CancellationToken token)
    {
        var startInfo = new ProcessStartInfo
        {
            FileName = _adbPath,
            Arguments = arguments,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true,
            StandardOutputEncoding = Encoding.UTF8,
            StandardErrorEncoding = Encoding.UTF8
        };

        using var process = new Process { StartInfo = startInfo };
        try
        {
            process.Start();
        }
        catch (Exception ex)
        {
            Logger.Debug("adb " + arguments + " could not start: " + ex.Message);
            return new AdbResult(-1, string.Empty, ex.Message);
        }

        var stdout = process.StandardOutput.ReadToEndAsync();
        var stderr = process.StandardError.ReadToEndAsync();

        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(token);
        timeout.CancelAfter(timeoutMs);

        try
        {
            await process.WaitForExitAsync(timeout.Token).ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
            try { process.Kill(true); } catch { /* ignorato */ }
            Logger.Debug("adb " + arguments + " timed out after " + timeoutMs + " ms");
            return new AdbResult(-1, string.Empty, "timeout");
        }

        var outText = await stdout.ConfigureAwait(false);
        var errText = await stderr.ConfigureAwait(false);
        return new AdbResult(process.ExitCode, outText, errText);
    }

    public Task<AdbResult> StartServerAsync(CancellationToken token) =>
        RunAsync("start-server", 15000, token);

    public async Task<List<AdbDevice>> ListDevicesAsync(CancellationToken token)
    {
        var devices = new List<AdbDevice>();
        var result = await RunAsync("devices -l", 8000, token).ConfigureAwait(false);
        if (!result.Success)
        {
            return devices;
        }

        foreach (var raw in result.StandardOutput.Split('\n'))
        {
            var line = raw.Trim();
            if (line.Length == 0 ||
                line.StartsWith("List of devices", StringComparison.OrdinalIgnoreCase) ||
                line.StartsWith("*", StringComparison.Ordinal))
            {
                continue;
            }

            var parts = line.Split(new[] { ' ', '\t' }, StringSplitOptions.RemoveEmptyEntries);
            if (parts.Length < 2)
            {
                continue;
            }

            var serial = parts[0];

            // Solo USB: i dispositivi "adb connect" (host:porta) vengono ignorati.
            if (serial.Contains(':', StringComparison.Ordinal))
            {
                continue;
            }

            var state = parts[1];
            var model = string.Empty;
            foreach (var part in parts)
            {
                if (part.StartsWith("model:", StringComparison.OrdinalIgnoreCase))
                {
                    model = part.Substring("model:".Length);
                }
            }

            devices.Add(new AdbDevice(serial, state, model));
        }

        return devices;
    }

    /// <summary>
    /// Crea il tunnel USB: una porta di loopback sul PC viene collegata al socket
    /// unix astratto esposto dall'app Android. Ritorna la porta locale, o 0.
    /// </summary>
    public async Task<int> ForwardAsync(string serial, string abstractSocketName, CancellationToken token)
    {
        var result = await RunAsync(
            "-s " + serial + " forward tcp:0 localabstract:" + abstractSocketName, 8000, token).ConfigureAwait(false);

        if (result.Success)
        {
            var text = result.StandardOutput.Trim();
            if (int.TryParse(text, NumberStyles.Integer, CultureInfo.InvariantCulture, out var port) && port > 0)
            {
                return port;
            }
        }

        // adb molto vecchi non supportano tcp:0: si scegle una porta libera a mano.
        var fallbackPort = FindFreePort();
        if (fallbackPort == 0)
        {
            Logger.Warn("adb forward failed: " + Describe(result));
            return 0;
        }

        var retry = await RunAsync(
            "-s " + serial + " forward tcp:" + fallbackPort + " localabstract:" + abstractSocketName,
            8000,
            token).ConfigureAwait(false);

        if (retry.Success)
        {
            return fallbackPort;
        }

        Logger.Warn("adb forward failed: " + Describe(retry));
        return 0;
    }

    public async Task RemoveForwardAsync(string serial, int port, CancellationToken token)
    {
        if (port <= 0)
        {
            return;
        }

        await RunAsync("-s " + serial + " forward --remove tcp:" + port, 5000, token).ConfigureAwait(false);
    }

    public async Task LaunchAppAsync(string serial, string component, CancellationToken token)
    {
        var result = await RunAsync(
            "-s " + serial + " shell am start -n " + component, 8000, token).ConfigureAwait(false);

        if (!result.Success)
        {
            Logger.Debug("Could not launch the Android app automatically: " + Describe(result));
        }
    }

    private static string Describe(AdbResult result)
    {
        var text = string.IsNullOrWhiteSpace(result.StandardError) ? result.StandardOutput : result.StandardError;
        return (text ?? string.Empty).Replace("\r", string.Empty).Replace("\n", " ").Trim();
    }

    private static int FindFreePort()
    {
        try
        {
            var listener = new TcpListener(IPAddress.Loopback, 0);
            listener.Start();
            var port = ((IPEndPoint)listener.LocalEndpoint).Port;
            listener.Stop();
            return port;
        }
        catch
        {
            return 0;
        }
    }
}
