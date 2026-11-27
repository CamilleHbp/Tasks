using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;

namespace Companion;

/// Routes widget links to the desktop app when installed, otherwise to the installed
/// web app, otherwise to the website in the default browser.
internal static class Router
{
    public const string WebOrigin = "https://tasks.camille.studio";

    public abstract record Action;
    public sealed record Open : Action;
    public sealed record Add : Action;
    public sealed record ShowSettings : Action;
    public sealed record OpenTask(string Id) : Action;

    public static Action? Parse(Uri? uri)
    {
        if (uri == null || uri.Scheme != "vervetwodo-widgets" || uri.OriginalString.Length > 4096) return null;
        switch (uri.Host)
        {
            case "open": return new Open();
            case "add": return new Add();
            case "settings": return new ShowSettings();
            case "task":
                var id = uri.Query.TrimStart('?').Split('&').Select(part => part.Split('=', 2))
                    .Where(pair => pair.Length == 2 && pair[0] == "id").Select(pair => Uri.UnescapeDataString(pair[1])).FirstOrDefault() ?? "";
                return id.Length is >= 16 and <= 128 && id.All(c => char.IsAsciiLetterOrDigit(c) || c == '-') ? new OpenTask(id) : null;
            default: return null;
        }
    }

    public static string Target
    {
        get => Settings.Get("openTarget") is "desktop" or "web" ? Settings.Get("openTarget")! : "automatic";
        set => Settings.Set("openTarget", value);
    }

    public static void Route(Action action)
    {
        if (Target != "web" && Handled("vervetwodo-preview"))
        {
            Shell(action switch
            {
                OpenTask task => "vervetwodo-preview://task?id=" + Uri.EscapeDataString(task.Id),
                Add => "vervetwodo-preview://add",
                _ => "vervetwodo-preview://open",
            });
            return;
        }
        // The installed web app declares this protocol handler; the browser registers it with
        // Windows, which then hands these links to that app.
        if (Handled("web+vervetwodo"))
        {
            Shell(action switch
            {
                OpenTask task => "web+vervetwodo://task?id=" + Uri.EscapeDataString(task.Id),
                Add => "web+vervetwodo://add",
                _ => "web+vervetwodo://open",
            });
            return;
        }
        Shell(WebOrigin + "/" + action switch
        {
            OpenTask task => "?task=" + Uri.EscapeDataString(task.Id) + "&widget=1",
            Add => "?new=1&widget=1",
            _ => "?widget=1",
        });
    }

    private static void Shell(string target)
    {
        try { Process.Start(new ProcessStartInfo(target) { UseShellExecute = true }); }
        catch (System.ComponentModel.Win32Exception) { }
    }

    [DllImport("shlwapi.dll", CharSet = CharSet.Unicode)]
    private static extern int AssocQueryString(int flags, int str, string assoc, string? extra, StringBuilder? output, ref uint size);

    /// Whether Windows has a registered handler for a URL scheme.
    private static bool Handled(string scheme)
    {
        uint size = 0;
        // ASSOCSTR_EXECUTABLE = 2; S_FALSE (1) reports the required buffer size for a known handler.
        var result = AssocQueryString(0, 2, scheme, null, null, ref size);
        return result is 0 or 1 && size > 0;
    }
}
