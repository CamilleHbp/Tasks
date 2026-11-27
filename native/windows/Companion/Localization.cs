using System.Globalization;
using System.Text.Json;
using Windows.Storage;

namespace Companion;

/// Follows the Windows display language by default, falls back to English, and keeps the
/// persistent in-app override (Automatic, English, Français).
internal static class Localization
{
    private static readonly Dictionary<string, Dictionary<string, string>> Tables = new[] { "en", "fr" }.ToDictionary(
        locale => locale, locale =>
        {
            using var resource = typeof(Localization).Assembly.GetManifestResourceStream($"Companion.Strings.{locale}.json")
                ?? throw new InvalidOperationException("Missing translations");
            return JsonSerializer.Deserialize<Dictionary<string, string>>(resource)!;
        });

    public static string Language
    {
        get => Settings.Get("language") is "en" or "fr" ? Settings.Get("language")! : "system";
        set => Settings.Set("language", value);
    }

    public static string Resolved => Language switch
    {
        "en" => "en",
        "fr" => "fr",
        _ => CultureInfo.CurrentUICulture.TwoLetterISOLanguageName == "fr" ? "fr" : "en",
    };

    public static string L(string key) =>
        Tables[Resolved].TryGetValue(key, out var value) ? value : Tables["en"].GetValueOrDefault(key, key);

    public static string L(string key, params object[] arguments) =>
        string.Format(CultureInfo.GetCultureInfo(Resolved), L(key), arguments);

    /// Picks the "one"/"other" form; French treats 0 and 1 as singular.
    public static string Plural(string key, long count) =>
        L(key + ((Resolved == "fr" ? count is 0 or 1 : count == 1) ? ".one" : ".other"), count);
}

internal static class Settings
{
    private static IDictionary<string, object> Values => ApplicationData.Current.LocalSettings.Values;
    public static string? Get(string key) => Values.TryGetValue(key, out var value) ? value as string : null;
    public static double GetNumber(string key) => Values.TryGetValue(key, out var value) && value is double number ? number : 0;
    public static void Set(string key, object value) => Values[key] = value;
}
