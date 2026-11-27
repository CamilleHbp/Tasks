using System.Diagnostics;
using System.Net.Http.Json;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using Windows.ApplicationModel;
using Windows.Management.Deployment;

namespace Companion;

/// Self-update from the shared download catalogue. The package is self-signed, so trust rests
/// on HTTPS plus the catalogue's size and SHA-256; Windows additionally refuses an update whose
/// publisher or signature does not match the installed package.
internal sealed class Updater
{
    private sealed record Asset(string Platform, string Channel, bool Primary, long Size, string Sha256, string Url);
    private sealed record Release(string Id, string Version, Asset[] Assets);
    private sealed record Catalogue(Release[] Releases);

    public const string Server = "https://download.camille.studio";
    private static readonly HttpClient Http = new() { Timeout = TimeSpan.FromMinutes(5) };

    public enum State { Idle, Checking, UpToDate, Available, Downloading, Failed }
    public State Current { get; private set; }
    public string? AvailableVersion { get; private set; }
    public string? Failure { get; private set; }
    public event Action? Changed;
    private (Release Release, Asset Asset)? found;

    public static string Version
    {
        get { var v = Package.Current.Id.Version; return $"{v.Major}.{v.Minor}.{v.Build}"; }
    }

    public static bool Newer(string a, string b)
    {
        var x = a.Split('.').Select(p => int.TryParse(p, out var n) ? n : 0).ToArray();
        var y = b.Split('.').Select(p => int.TryParse(p, out var n) ? n : 0).ToArray();
        for (var i = 0; i < Math.Max(x.Length, y.Length); i++)
        {
            var l = i < x.Length ? x[i] : 0;
            var r = i < y.Length ? y[i] : 0;
            if (l != r) return l > r;
        }
        return false;
    }

    private void Set(State state, string? failure = null) { Current = state; Failure = failure; Changed?.Invoke(); }

    /// Automatic checks run at most daily and respect Later; manual checks bypass both.
    public async Task Check(bool manual)
    {
        var now = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        if (!manual && now - Settings.GetNumber("updateCheckedAt") < 24 * 3600) return;
        if (Current is State.Checking or State.Downloading) return;
        Set(State.Checking);
        try
        {
            var catalogue = await Http.GetFromJsonAsync<Catalogue>(Server + "/api/v1/vervetwodo-widgets.json",
                new System.Text.Json.JsonSerializerOptions { PropertyNameCaseInsensitive = true });
            Settings.Set("updateCheckedAt", (double)now);
            var best = (catalogue?.Releases ?? [])
                .Select(r => (Release: r, Asset: r.Assets.FirstOrDefault(a => a.Platform == "windows" && a.Primary && a.Channel == "release")))
                .Where(c => c.Asset != null)
                .Aggregate(((Release, Asset)?)null, (top, c) => top == null || Newer(c.Release.Version, top.Value.Item1.Version) ? (c.Release, c.Asset!) : top);
            if (best == null || !Newer(best.Value.Item1.Version, Version)) { found = null; Set(State.UpToDate); return; }
            if (!manual && Settings.Get("updateSnoozedVersion") == best.Value.Item1.Version && now < Settings.GetNumber("updateSnoozedUntil"))
            {
                Set(State.Idle);
                return;
            }
            found = best;
            AvailableVersion = best.Value.Item1.Version;
            Set(State.Available);
        }
        catch (Exception error) when (error is HttpRequestException or TaskCanceledException or System.Text.Json.JsonException)
        {
            Set(State.Failed, Localization.L("update.failed.check"));
        }
    }

    public void Later()
    {
        if (AvailableVersion == null) return;
        Settings.Set("updateSnoozedVersion", AvailableVersion);
        Settings.Set("updateSnoozedUntil", (double)DateTimeOffset.UtcNow.ToUnixTimeSeconds() + 24 * 3600);
        Set(State.Idle);
    }

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)]
    private static extern int RegisterApplicationRestart(string? arguments, int flags);

    public async Task Install()
    {
        if (found is not { } update) return;
        Set(State.Downloading);
        var staging = Path.Combine(Path.GetTempPath(), "VerveTwoDoWidgets-" + update.Release.Version);
        try
        {
            if (Directory.Exists(staging)) Directory.Delete(staging, true);
            Directory.CreateDirectory(staging);
            var installer = Path.Combine(staging, "setup.exe");
            await using (var stream = await Http.GetStreamAsync(Server + update.Asset.Url))
            await using (var file = File.Create(installer)) await stream.CopyToAsync(file);
            string digest;
            await using (var file = File.OpenRead(installer))
            {
                if (file.Length != update.Asset.Size) throw new InvalidDataException();
                digest = Convert.ToHexString(await SHA256.HashDataAsync(file)).ToLowerInvariant();
            }
            if (digest != update.Asset.Sha256.ToLowerInvariant()) throw new InvalidDataException();
            // The release asset is the setup program; it only unpacks here, so updating needs no prompt.
            var payload = Path.Combine(staging, "payload");
            using (var process = Process.Start(new ProcessStartInfo(installer, ["--extract", payload]) { UseShellExecute = false, CreateNoWindow = true })
                                 ?? throw new InvalidOperationException())
            {
                await process.WaitForExitAsync();
                if (process.ExitCode != 0) throw new InvalidDataException();
            }
            var package = Directory.EnumerateFiles(payload, "*.msix").Single();
            // Windows relaunches the companion after servicing replaces it.
            RegisterApplicationRestart(null, 0);
            var result = await new PackageManager().AddPackageAsync(new Uri(package), null, DeploymentOptions.ForceApplicationShutdown);
            if (result.ExtendedErrorCode != null) throw new InvalidOperationException(result.ErrorText);
        }
        catch (Exception error) when (error is InvalidDataException or InvalidOperationException)
        {
            Set(State.Failed, Localization.L("update.failed.verify"));
        }
        catch (Exception error) when (error is HttpRequestException or IOException or TaskCanceledException or COMException or System.ComponentModel.Win32Exception)
        {
            Set(State.Failed, Localization.L("update.failed.install"));
        }
    }
}
