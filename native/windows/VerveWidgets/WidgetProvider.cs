using System.Collections.Concurrent;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text.Json;
using Microsoft.Windows.Widgets.Providers;

namespace VerveWidgets;

[ComVisible(true)]
[Guid("56F0B272-F86A-45B1-A536-E8FC833F2017")]
public class WidgetProvider : IWidgetProvider
{
    // WinRT callback objects are valid only during their callback; retain managed values.
    private sealed record Context(string Id, string Size);
    private readonly ConcurrentDictionary<string, Context> contexts = new();
    private readonly FileSystemWatcher watcher;
    private readonly Timer debounce;

    public WidgetProvider()
    {
        foreach (var info in WidgetManager.GetDefault().GetWidgetInfos()) Remember(info.WidgetContext);
        Directory.CreateDirectory(WidgetData.DirectoryPath);
        debounce = new Timer(_ => RefreshAll(), null, Timeout.Infinite, Timeout.Infinite);
        watcher = new FileSystemWatcher(WidgetData.DirectoryPath, "snapshot.json") { NotifyFilter = NotifyFilters.LastWrite | NotifyFilters.FileName };
        watcher.Changed += (_, _) => debounce.Change(250, Timeout.Infinite);
        watcher.Created += (_, _) => debounce.Change(250, Timeout.Infinite);
        watcher.Renamed += (_, _) => debounce.Change(250, Timeout.Infinite);
        watcher.EnableRaisingEvents = true;
    }

    private Context Remember(WidgetContext context) {
        var value = new Context(context.Id, context.Size.ToString());
        contexts[value.Id] = value;
        return value;
    }
    public void CreateWidget(WidgetContext context) { Update(Remember(context)); }
    public void DeleteWidget(string widgetId, string customState) { contexts.TryRemove(widgetId, out _); }
    public void Activate(WidgetContext context) { Update(Remember(context)); }
    public void Deactivate(string widgetId) { }
    public void OnWidgetContextChanged(WidgetContextChangedArgs args) { Update(Remember(args.WidgetContext)); }
    public void OnActionInvoked(WidgetActionInvokedArgs args)
    {
        if (args.Verb != "complete") return;
        try
        {
            using var payload = JsonDocument.Parse(args.Data);
            WidgetData.Complete(payload.RootElement.GetProperty("taskId").GetString() ?? "");
            Process.Start(new ProcessStartInfo("vervetwodo-preview://open") { UseShellExecute = true });
        }
        catch (Exception error) when (error is JsonException or IOException or InvalidOperationException or System.ComponentModel.Win32Exception) { }
    }
    private void RefreshAll() { foreach (var context in contexts.Values) Update(context); }
    private static void Update(Context context)
    {
        try
        {
            var snapshot = WidgetData.Read();
            var limit = context.Size switch { "Small" => 2, "Large" => 6, _ => 3 };
            WidgetManager.GetDefault().UpdateWidget(new WidgetUpdateRequestOptions(context.Id) {
                Template = WidgetData.Card(snapshot, limit), Data = JsonSerializer.Serialize(new { tasks = snapshot.Tasks.Where(task => !task.Completed) }, WidgetData.Json), CustomState = "{}"
            });
        }
        catch (COMException) { /* The host can remove a widget while an update is in flight. */ }
    }
}
