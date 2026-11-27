using System.Collections.Concurrent;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.Globalization;
using System.Runtime.InteropServices;
using System.Text.Json;
using Microsoft.Windows.Widgets.Providers;

namespace Companion;

internal sealed record TaskRow(string Id, string Title, bool Completed, string? DueDate, string? DueTime = null);
internal sealed record Snapshot(int Version, long Revision, string Locale, TaskRow[] Tasks, bool Available = true);
/// One bound row of the card. Titles reach the card only as data, never as template text.
internal sealed record CardRow(string Id, string Title, string Due, bool Overdue, bool Done, string Icon, string Label);

internal static class WidgetData
{
    public static readonly JsonSerializerOptions Json = new() { PropertyNamingPolicy = JsonNamingPolicy.CamelCase };

    /// Taps show at once, before the helper applies them. A task checked in the widget stays
    /// in place, checked, for a short while so a mistaken tap can be undone where it happened.
    public static readonly TimeSpan Window = TimeSpan.FromMinutes(10);
    private static readonly ConcurrentDictionary<string, (bool Completed, DateTime At)> Overrides = new();

    public static Snapshot Read()
    {
        try
        {
            var file = new FileInfo(Path.Combine(Helper.WidgetDirectory, "snapshot.json"));
            if (file.Length > 4 * 1024 * 1024) throw new InvalidDataException();
            var value = JsonSerializer.Deserialize<Snapshot>(File.ReadAllText(file.FullName), Json);
            if (value is { Version: 1, Tasks: not null }) return value with { Available = true };
        }
        catch (Exception error) when (error is IOException or JsonException or UnauthorizedAccessException or InvalidDataException) { }
        return new Snapshot(1, 0, Localization.Resolved, [], false);
    }

    /// The helper drains commands every half second; "completed": false reopens the task.
    public static void Toggle(string taskId, bool completed)
    {
        if (taskId.Length is < 16 or > 128 || !Read().Tasks.Any(task => task.Id == taskId)) throw new InvalidDataException("Unknown task");
        var directory = Path.Combine(Helper.WidgetDirectory, "commands");
        Directory.CreateDirectory(directory);
        var id = Guid.NewGuid().ToString();
        // Name order is apply order, so a quick check then uncheck stays in sequence.
        var name = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds().ToString("D13") + "-" + id;
        var temporary = Path.Combine(directory, name + ".tmp");
        File.WriteAllText(temporary, JsonSerializer.Serialize(new { version = 1, id, action = "complete", taskId, title = "", completed }));
        File.Move(temporary, Path.Combine(directory, name + ".json"));
        Overrides[taskId] = (completed, DateTime.UtcNow);
    }

    /// When the oldest checked row should leave the widget.
    public static DateTime? NextExpiry()
    {
        foreach (var (id, value) in Overrides) if (DateTime.UtcNow - value.At >= Window) Overrides.TryRemove(id, out _);
        return Overrides.IsEmpty ? null : Overrides.Values.Min(value => value.At) + Window;
    }

    /// Open tasks, dated ones first, matching the default widget ordering on macOS, plus tasks
    /// checked in the widget during the last few minutes.
    public static TaskRow[] Rows(Snapshot snapshot)
    {
        NextExpiry();
        return snapshot.Tasks
            .Select(task => Overrides.TryGetValue(task.Id, out var value) ? task with { Completed = value.Completed } : task)
            .Where(task => !task.Completed || Overrides.TryGetValue(task.Id, out var value) && value.Completed)
            .OrderBy(task => string.IsNullOrEmpty(task.DueDate)).ThenBy(task => task.DueDate)
            .ThenBy(task => string.IsNullOrEmpty(task.DueTime)).ThenBy(task => task.DueTime)
            .ThenBy(task => task.Title, StringComparer.CurrentCultureIgnoreCase).ToArray();
    }

    public static CardRow[] CardRows(Snapshot snapshot, int limit) => Rows(snapshot).Take(limit).Select(task => new CardRow(
        task.Id, task.Title, Due(task), IsOverdue(task), task.Completed, task.Completed ? Checked : Unchecked,
        Localization.L(task.Completed ? "widget.markNotDone" : "widget.markDone", task.Title))).ToArray();

    private static bool IsOverdue(TaskRow task) => !task.Completed &&
        DateOnly.TryParseExact(task.DueDate, "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out var date) &&
        date < DateOnly.FromDateTime(DateTime.Now);

    private static string Due(TaskRow task)
    {
        if (!DateOnly.TryParseExact(task.DueDate, "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out var date)) return "";
        var today = DateOnly.FromDateTime(DateTime.Now);
        var culture = CultureInfo.GetCultureInfo(Localization.Resolved);
        var day = date == today ? Localization.L("widget.today") : date == today.AddDays(1) ? Localization.L("widget.tomorrow")
            : date.ToString(culture.DateTimeFormat.MonthDayPattern, culture);
        if (date < today) day = Localization.L("widget.overdue", day);
        return string.IsNullOrEmpty(task.DueTime) ? day : day + " · " + task.DueTime;
    }

    public static string Card(Snapshot snapshot, CardRow[] rows)
    {
        string T(string key) => Localization.L(key);
        var open = Rows(snapshot).Count(task => !task.Completed);
        var body = new List<object> { new { type = "ColumnSet", columns = new object[] {
            new { type = "Column", width = "stretch", items = new object[] { new { type = "TextBlock", text = T("widget.title"), weight = "Bolder", size = "Medium" } } },
            new { type = "Column", width = "auto", verticalContentAlignment = "Center", items = new object[] {
                new { type = "TextBlock", text = open > 0 ? open.ToString(CultureInfo.InvariantCulture) : "", isSubtle = true, size = "Medium" } } }
        } } };
        var actions = new List<object>();
        if (!snapshot.Available)
        {
            body.Add(new { type = "TextBlock", text = T("widget.connect"), wrap = true });
            actions.Add(new { type = "Action.OpenUrl", title = T("widget.settings"), url = "vervetwodo-widgets://settings" });
        }
        else
        {
            for (var index = 0; index < rows.Length; index++)
            {
                var row = rows[index];
                var path = "${rows[" + index + "].";
                var text = new List<object> { new { type = "TextBlock", text = path + "title}", wrap = true, maxLines = 2, isSubtle = row.Done } };
                if (row.Due.Length > 0) text.Add(new { type = "TextBlock", text = path + "due}", isSubtle = true, size = "Small", spacing = "None",
                    color = row.Overdue ? "Attention" : "Default" });
                // The whole leading column is the checkbox; the rest of the row opens the task.
                body.Add(new { type = "ColumnSet", spacing = "Small", columns = new object[] {
                    new { type = "Column", width = "auto", verticalContentAlignment = "Center",
                        selectAction = new { type = "Action.Execute", verb = "toggle", tooltip = path + "label}", data = new { taskId = row.Id, completed = !row.Done } },
                        items = new object[] { new { type = "Image", url = path + "icon}", altText = path + "label}", width = "20px", height = "20px" } } },
                    new { type = "Column", width = "stretch", verticalContentAlignment = "Center",
                        selectAction = new { type = "Action.OpenUrl", url = "vervetwodo-widgets://task?id=" + Uri.EscapeDataString(row.Id), tooltip = T("widget.open") },
                        items = text.ToArray() }
                } });
            }
            if (rows.Length == 0) body.Add(new { type = "TextBlock", text = T("widget.empty"), wrap = true, isSubtle = true });
            actions.Add(new { type = "Action.OpenUrl", title = T("widget.add"), url = "vervetwodo-widgets://add" });
        }
        return JsonSerializer.Serialize(new Dictionary<string, object> {
            ["$schema"] = "http://adaptivecards.io/schemas/adaptive-card.json",
            ["type"] = "AdaptiveCard", ["version"] = "1.5", ["body"] = body, ["actions"] = actions
        });
    }

    // Drawn once at 2x: a brand-blue ring, and the same circle filled with a white check.
    // The brand blue reads on both the light and dark widget boards.
    private static readonly string Unchecked = Icon(done: false);
    private static readonly string Checked = Icon(done: true);

    private static string Icon(bool done)
    {
        using var bitmap = new Bitmap(40, 40);
        using (var graphics = Graphics.FromImage(bitmap))
        {
            graphics.SmoothingMode = SmoothingMode.AntiAlias;
            var blue = Color.FromArgb(0x38, 0x77, 0xB8);
            if (done)
            {
                using var fill = new SolidBrush(blue);
                graphics.FillEllipse(fill, 2, 2, 36, 36);
                using var check = new Pen(Color.White, 4) { StartCap = LineCap.Round, EndCap = LineCap.Round, LineJoin = LineJoin.Round };
                graphics.DrawLines(check, new[] { new PointF(11, 20.5f), new PointF(17.5f, 27), new PointF(29, 14) });
            }
            else
            {
                using var ring = new Pen(blue, 3.5f);
                graphics.DrawEllipse(ring, 3.75f, 3.75f, 32.5f, 32.5f);
            }
        }
        using var stream = new MemoryStream();
        bitmap.Save(stream, ImageFormat.Png);
        return "data:image/png;base64," + Convert.ToBase64String(stream.ToArray());
    }
}

[ComVisible(true)]
[Guid("8E3CA270-6827-43B9-BED2-13E3E5041AF9")]
public sealed class WidgetProvider : IWidgetProvider
{
    // WinRT callback objects are valid only during their callback; retain managed values.
    private sealed record Context(string Id, string Size);
    private readonly ConcurrentDictionary<string, Context> contexts = new();
    private readonly FileSystemWatcher watcher;
    private readonly System.Threading.Timer debounce;
    private readonly System.Threading.Timer expire;

    public WidgetProvider()
    {
        foreach (var info in WidgetManager.GetDefault().GetWidgetInfos()) Remember(info.WidgetContext);
        Directory.CreateDirectory(Helper.WidgetDirectory);
        debounce = new System.Threading.Timer(_ => RefreshAll(), null, Timeout.Infinite, Timeout.Infinite);
        expire = new System.Threading.Timer(_ => RefreshAll(), null, Timeout.Infinite, Timeout.Infinite);
        watcher = new FileSystemWatcher(Helper.WidgetDirectory, "snapshot.json") { NotifyFilter = NotifyFilters.LastWrite | NotifyFilters.FileName };
        watcher.Changed += (_, _) => debounce.Change(250, Timeout.Infinite);
        watcher.Created += (_, _) => debounce.Change(250, Timeout.Infinite);
        watcher.Deleted += (_, _) => debounce.Change(250, Timeout.Infinite);
        watcher.Renamed += (_, _) => debounce.Change(250, Timeout.Infinite);
        watcher.EnableRaisingEvents = true;
    }

    private Context Remember(WidgetContext context)
    {
        var value = new Context(context.Id, context.Size.ToString());
        contexts[value.Id] = value;
        return value;
    }
    public void CreateWidget(WidgetContext context) => Update(Remember(context));
    public void DeleteWidget(string widgetId, string customState) => contexts.TryRemove(widgetId, out _);
    public void Activate(WidgetContext context) => Update(Remember(context));
    public void Deactivate(string widgetId) { }
    public void OnWidgetContextChanged(WidgetContextChangedArgs args) => Update(Remember(args.WidgetContext));
    public void OnActionInvoked(WidgetActionInvokedArgs args)
    {
        if (args.Verb != "toggle" && args.Verb != "complete") return;
        try
        {
            using var payload = JsonDocument.Parse(args.Data);
            var completed = !payload.RootElement.TryGetProperty("completed", out var value) || value.ValueKind != JsonValueKind.False;
            WidgetData.Toggle(payload.RootElement.GetProperty("taskId").GetString() ?? "", completed);
            RefreshAll();
        }
        catch (Exception error) when (error is JsonException or IOException or InvalidDataException or KeyNotFoundException or InvalidOperationException) { }
    }
    public void RefreshAll()
    {
        foreach (var context in contexts.Values) Update(context);
        // Refresh again when the oldest checked row should leave.
        if (WidgetData.NextExpiry() is { } expiry)
            expire.Change((long)Math.Clamp((expiry - DateTime.UtcNow).TotalMilliseconds + 500, 0, int.MaxValue), Timeout.Infinite);
    }
    private static void Update(Context context)
    {
        try
        {
            var snapshot = WidgetData.Read();
            var limit = context.Size switch { "Small" => 2, "Large" => 7, _ => 4 };
            var rows = WidgetData.CardRows(snapshot, limit);
            WidgetManager.GetDefault().UpdateWidget(new WidgetUpdateRequestOptions(context.Id) {
                Template = WidgetData.Card(snapshot, rows),
                Data = JsonSerializer.Serialize(new { rows }, WidgetData.Json), CustomState = "{}"
            });
        }
        catch (COMException) { /* The host can remove a widget while an update is in flight. */ }
    }
}
