using System.Globalization;
using System.Text.Json;

namespace VerveWidgets;

public record TaskRow(string Id, string Title, bool Completed);
public record Snapshot(int Version, long Revision, string Locale, TaskRow[] Tasks);

internal static class WidgetData
{
    private static readonly Dictionary<string, Dictionary<string, string>> Strings = new[] { "en", "fr" }.ToDictionary(
        locale => locale, locale => {
            using var resource = typeof(WidgetData).Assembly.GetManifestResourceStream($"VerveWidgets.Strings.{locale}.json")
                ?? throw new InvalidOperationException("Missing widget translations");
            return JsonSerializer.Deserialize<Dictionary<string, string>>(resource)!;
        });
    public static readonly JsonSerializerOptions Json = new() { PropertyNamingPolicy = JsonNamingPolicy.CamelCase };
    public static string DirectoryPath => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "VerveTwoDo", "Preview");
    public static Snapshot Read()
    {
        try
        {
            var file = new FileInfo(Path.Combine(DirectoryPath, "snapshot.json"));
            if (file.Length > 4 * 1024 * 1024) throw new InvalidDataException();
            var value = JsonSerializer.Deserialize<Snapshot>(File.ReadAllText(file.FullName), Json);
            if (value is { Version: 1, Tasks: not null }) return value;
        }
        catch (Exception error) when (error is IOException or JsonException or UnauthorizedAccessException) { }
        return new Snapshot(1, 0, CultureInfo.CurrentUICulture.TwoLetterISOLanguageName, []);
    }

    public static void Complete(string taskId)
    {
        if (taskId.Length is < 16 or > 128 || !Read().Tasks.Any(task => task.Id == taskId)) throw new InvalidDataException("Unknown task");
        var directory = Path.Combine(DirectoryPath, "commands");
        Directory.CreateDirectory(directory);
        var id = Guid.NewGuid().ToString();
        var temporary = Path.Combine(directory, id + ".tmp");
        File.WriteAllText(temporary, JsonSerializer.Serialize(new { version = 1, id, action = "complete", taskId, title = "", completed = true }));
        File.Move(temporary, Path.Combine(directory, id + ".json"));
    }

    public static string Card(Snapshot snapshot, int limit)
    {
        var text = Strings[snapshot.Locale == "fr" ? "fr" : "en"];
        var body = new List<object> { new { type = "TextBlock", text = "VerveTwoDo", weight = "Bolder", size = "Medium" } };
        var index = 0;
        foreach (var task in snapshot.Tasks.Where(task => !task.Completed).Take(limit))
        {
            body.Add(new { type = "TextBlock", text = "${tasks[" + index++ + "].title}", wrap = true });
            body.Add(new { type = "ActionSet", actions = new object[] {
                new { type = "Action.Execute", title = text["complete"], verb = "complete", data = new { taskId = task.Id } },
                new { type = "Action.OpenUrl", title = text["open"], url = "vervetwodo-preview://task?id=" + Uri.EscapeDataString(task.Id) }
            } });
        }
        if (!snapshot.Tasks.Any(task => !task.Completed))
            body.Add(new { type = "TextBlock", text = text["empty"], wrap = true });
        return JsonSerializer.Serialize(new Dictionary<string, object> {
            ["$schema"] = "http://adaptivecards.io/schemas/adaptive-card.json",
            ["type"] = "AdaptiveCard", ["version"] = "1.5", ["body"] = body,
            ["actions"] = new[] { new { type = "Action.OpenUrl", title = text["add"], url = "vervetwodo-preview://add" } }
        });
    }
}
