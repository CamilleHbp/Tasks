using System.Collections.Concurrent;
using System.Diagnostics;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace Companion;

internal sealed record HelperStatus(bool Authenticated = false, string Workspace = "", string Origin = "", bool Confirmed = false,
    long LastSuccessAt = 0, bool Busy = false, string? Error = null, int Tasks = 0);

internal sealed record HelperResponse(string? Id, string? Event, HelperStatus? Status, string? Error);

internal sealed class HelperException(string code) : Exception(Localization.L("error." + code) is var text && text != "error." + code ? text : Localization.L("error.generic"))
{
    public string Code { get; } = code;
}

/// Owns the bundled Go helper, which syncs the workspace and keeps the widget files current.
/// Access codes cross only the helper's stdin, never arguments or files.
internal sealed class Helper
{
    public static string DataDirectory => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "VerveTwoDo Widgets");
    public static string WidgetDirectory => Path.Combine(DataDirectory, "widget");

    private static readonly JsonSerializerOptions Json = new() { PropertyNamingPolicy = JsonNamingPolicy.CamelCase, DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull };
    private readonly ConcurrentDictionary<string, TaskCompletionSource<HelperResponse>> pending = new();
    private readonly object gate = new();
    private Process? process;
    private bool stopping;

    public HelperStatus Status { get; private set; } = new();
    public bool Running => process is { HasExited: false };
    public event Action? Changed;

    public void Start()
    {
        lock (gate)
        {
            if (Running || stopping) return;
            var executable = Path.Combine(AppContext.BaseDirectory, "vervetwodo-widget-helper.exe");
            var info = new ProcessStartInfo(executable)
            {
                RedirectStandardInput = true, RedirectStandardOutput = true, RedirectStandardError = true,
                UseShellExecute = false, CreateNoWindow = true,
            };
            foreach (var argument in new[] { "--data-dir", DataDirectory, "--widget-dir", WidgetDirectory, "--platform", "windows" })
                info.ArgumentList.Add(argument);
            process = Process.Start(info);
            if (process == null) return;
            process.EnableRaisingEvents = true;
            process.Exited += (_, _) => Restart();
            process.ErrorDataReceived += (_, _) => { };
            process.BeginErrorReadLine();
            var output = process.StandardOutput;
            new Thread(() => Read(output)) { IsBackground = true }.Start();
        }
        _ = SafeRequest("locale", new() { ["locale"] = Localization.Resolved });
    }

    public void Stop()
    {
        lock (gate)
        {
            stopping = true;
            try { process?.StandardInput.Close(); process?.WaitForExit(10_000); } catch (InvalidOperationException) { }
        }
    }

    private void Restart()
    {
        foreach (var id in pending.Keys)
            if (pending.TryRemove(id, out var waiter)) waiter.TrySetResult(new HelperResponse(id, null, null, "helper_stopped"));
        Changed?.Invoke();
        if (!stopping) Task.Delay(5000).ContinueWith(_ => Start());
    }

    private void Read(StreamReader output)
    {
        while (output.ReadLine() is { } line)
        {
            HelperResponse? response;
            try { response = JsonSerializer.Deserialize<HelperResponse>(line, Json); } catch (JsonException) { continue; }
            if (response == null) continue;
            if (response.Status != null) Status = response.Status;
            if (response.Id != null && pending.TryRemove(response.Id, out var waiter)) waiter.TrySetResult(response);
            Changed?.Invoke();
        }
    }

    public async Task<HelperStatus> Request(string method, Dictionary<string, string>? fields = null)
    {
        var id = Guid.NewGuid().ToString();
        var body = new Dictionary<string, string>(fields ?? new()) { ["id"] = id, ["method"] = method };
        var waiter = new TaskCompletionSource<HelperResponse>(TaskCreationOptions.RunContinuationsAsynchronously);
        pending[id] = waiter;
        try
        {
            lock (gate)
            {
                if (!Running) throw new HelperException("helper_stopped");
                process!.StandardInput.WriteLine(JsonSerializer.Serialize(body));
                process.StandardInput.Flush();
            }
        }
        catch (Exception error) when (error is IOException or InvalidOperationException)
        {
            pending.TryRemove(id, out _);
            throw new HelperException("helper_stopped");
        }
        var response = await waiter.Task;
        if (response.Error != null) throw new HelperException(response.Error);
        return response.Status ?? Status;
    }

    public async Task SafeRequest(string method, Dictionary<string, string>? fields = null)
    {
        try { await Request(method, fields); } catch (HelperException) { }
    }
}
