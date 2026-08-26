using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using RemoteVolumeMixer.Core;

namespace RemoteVolumeMixer.Audio;

/// <summary>
/// Estrae l'icona reale dell'eseguibile di una sessione audio e la converte in PNG.
/// Le icone sono indicizzate da una chiave stabile (hash della sorgente) e messe in
/// cache: la stessa icona non viaggia due volte sul cavo USB.
/// Se l'estrazione non e' possibile ritorna null: l'app Android disegnera' il proprio
/// fallback elegante, mai un'immagine rotta.
/// </summary>
public sealed class IconProvider
{
    public const string MasterIconKey = "__master__";
    public const string SystemSoundsIconKey = "__system__";

    private const int IconSize = 96;

    private readonly object _gate = new();
    private readonly Dictionary<string, string> _sources = new(StringComparer.Ordinal);
    private readonly Dictionary<string, string?> _pngCache = new(StringComparer.Ordinal);

    [DllImport("user32.dll", EntryPoint = "PrivateExtractIconsW", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern int PrivateExtractIcons(
        string fileName, int iconIndex, int cx, int cy, IntPtr[] icons, int[] iconIds, int iconCount, int flags);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool DestroyIcon(IntPtr handle);

    /// <summary>
    /// Registra la sorgente dell'icona per una sessione e ritorna la chiave stabile.
    /// Stringa vuota = nessuna sorgente utilizzabile.
    /// </summary>
    public string RegisterSource(string? executablePath, string? sessionIconPath, bool isSystemSounds, bool isMaster)
    {
        if (isMaster)
        {
            return MasterIconKey;
        }

        var source = NormalizeSource(sessionIconPath);
        if (source == null && !string.IsNullOrWhiteSpace(executablePath))
        {
            source = executablePath!.Trim();
        }

        if (source == null && isSystemSounds)
        {
            source = Environment.ExpandEnvironmentVariables(@"%SystemRoot%\System32\AudioSrv.Dll") + ",0";
        }

        if (source == null)
        {
            return string.Empty;
        }

        var key = isSystemSounds ? SystemSoundsIconKey : ShortHash(source.ToLowerInvariant());
        lock (_gate)
        {
            _sources[key] = source;
        }

        return key;
    }

    /// <summary>PNG base64 dell'icona, oppure null se non disponibile.</summary>
    public string? GetIconBase64(string iconKey)
    {
        if (string.IsNullOrEmpty(iconKey) || iconKey == MasterIconKey)
        {
            return null;
        }

        string source;
        lock (_gate)
        {
            if (_pngCache.TryGetValue(iconKey, out var cached))
            {
                return cached;
            }

            if (!_sources.TryGetValue(iconKey, out var found))
            {
                return null;
            }

            source = found;
        }

        string? png = null;
        try
        {
            png = Render(source);
        }
        catch (Exception ex)
        {
            Logger.Debug("Icon extraction failed for " + source + ": " + ex.Message);
        }

        lock (_gate)
        {
            _pngCache[iconKey] = png;
        }

        if (png == null)
        {
            Logger.Debug("No icon available for " + source + " (Android will use the fallback tile)");
        }

        return png;
    }

    private static string? Render(string source)
    {
        var (path, index) = SplitSource(source);
        if (string.IsNullOrWhiteSpace(path))
        {
            return null;
        }

        // Alcune app (tipicamente pacchetti UWP/MSIX) espongono direttamente un'immagine.
        var extension = Path.GetExtension(path).ToLowerInvariant();
        if (extension == ".png" || extension == ".jpg" || extension == ".jpeg" || extension == ".bmp")
        {
            if (!File.Exists(path))
            {
                return null;
            }

            using var image = Image.FromFile(path);
            using var resized = ResizeToPng(image);
            return ToBase64Png(resized);
        }

        if (!File.Exists(path))
        {
            return null;
        }

        using var bitmap = ExtractBitmap(path, index);
        if (bitmap == null)
        {
            return null;
        }

        return ToBase64Png(bitmap);
    }

    private static Bitmap? ExtractBitmap(string path, int index)
    {
        var handles = new IntPtr[1];
        var ids = new int[1];
        var extracted = 0;

        try
        {
            extracted = PrivateExtractIcons(path, index < 0 ? 0 : index, IconSize, IconSize, handles, ids, 1, 0);
        }
        catch (Exception ex)
        {
            Logger.Debug("PrivateExtractIcons threw for " + path + ": " + ex.Message);
        }

        if (extracted > 0 && handles[0] != IntPtr.Zero)
        {
            try
            {
                using var icon = Icon.FromHandle(handles[0]);
                return icon.ToBitmap();
            }
            finally
            {
                DestroyIcon(handles[0]);
            }
        }

        // Fallback: icona associata (32x32), meglio di niente.
        try
        {
            using var associated = Icon.ExtractAssociatedIcon(path);
            return associated?.ToBitmap();
        }
        catch (Exception ex)
        {
            Logger.Debug("ExtractAssociatedIcon failed for " + path + ": " + ex.Message);
            return null;
        }
    }

    private static Bitmap ResizeToPng(Image image)
    {
        var bitmap = new Bitmap(IconSize, IconSize, PixelFormat.Format32bppArgb);
        using var graphics = Graphics.FromImage(bitmap);
        graphics.InterpolationMode = System.Drawing.Drawing2D.InterpolationMode.HighQualityBicubic;
        graphics.DrawImage(image, 0, 0, IconSize, IconSize);
        return bitmap;
    }

    private static string ToBase64Png(Bitmap bitmap)
    {
        using var stream = new MemoryStream();
        bitmap.Save(stream, ImageFormat.Png);
        return Convert.ToBase64String(stream.ToArray());
    }

    private static (string path, int index) SplitSource(string source)
    {
        var value = source.Trim();
        if (value.StartsWith("@", StringComparison.Ordinal))
        {
            value = value.Substring(1);
        }

        value = Environment.ExpandEnvironmentVariables(value);

        var comma = value.LastIndexOf(',');
        if (comma > 1 && int.TryParse(value.Substring(comma + 1), out var index))
        {
            return (value.Substring(0, comma).Trim('"', ' '), index);
        }

        return (value.Trim('"', ' '), 0);
    }

    private static string? NormalizeSource(string? sessionIconPath)
    {
        if (string.IsNullOrWhiteSpace(sessionIconPath))
        {
            return null;
        }

        var (path, _) = SplitSource(sessionIconPath!);
        return string.IsNullOrWhiteSpace(path) ? null : sessionIconPath!.Trim();
    }

    private static string ShortHash(string value)
    {
        var bytes = SHA256.HashData(Encoding.UTF8.GetBytes(value));
        var builder = new StringBuilder(16);
        for (var i = 0; i < 8; i++)
        {
            builder.Append(bytes[i].ToString("x2"));
        }

        return builder.ToString();
    }
}
