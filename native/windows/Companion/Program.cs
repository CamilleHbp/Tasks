using System.Runtime.InteropServices;
using Microsoft.Windows.AppLifecycle;
using Microsoft.Windows.Widgets.Providers;
using Windows.ApplicationModel.Activation;
using WinRT;
using static Companion.Localization;

namespace Companion;

/// One resident process per user hosts the widget provider, the sync helper, the tray menu,
/// settings and updates. Protocol and COM launches are redirected to it.
internal static class Program
{
    [DllImport("ole32.dll")] private static extern int CoRegisterClassObject(ref Guid clsid, [MarshalAs(UnmanagedType.IUnknown)] object factory, uint context, uint flags, out uint cookie);
    [DllImport("ole32.dll")] private static extern int CoRevokeClassObject(uint cookie);

    private static readonly Helper helper = new();
    private static readonly Updater updater = new();
    private static WidgetProvider provider = null!;
    private static NotifyIcon? tray;
    private static SettingsForm? settings;
    private static Control? ui;

    [STAThread]
    private static int Main()
    {
        var activation = AppInstance.GetCurrent().GetActivatedEventArgs();
        var main = AppInstance.FindOrRegisterForKey("main");
        if (!main.IsCurrent)
        {
            main.RedirectActivationToAsync(activation).AsTask().Wait();
            return 0;
        }
        ApplicationConfiguration.Initialize();
        provider = new WidgetProvider();
        ui = new Control();
        ui.CreateControl();
        main.Activated += (_, args) => ui.BeginInvoke(() => Handle(args));

        // REGCLS_MULTIPLEUSE lets the Widgets host reuse this resident instance.
        var clsid = typeof(WidgetProvider).GUID;
        Marshal.ThrowExceptionForHR(CoRegisterClassObject(ref clsid, new ProviderFactory(provider), 4, 1, out var cookie));
        helper.Changed += () => ui.BeginInvoke(BuildMenu);
        updater.Changed += () => ui.BeginInvoke(BuildMenu);
        helper.Start();
        tray = new NotifyIcon { Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath), Visible = true };
        tray.MouseClick += (_, e) => { if (e.Button == MouseButtons.Left) ShowSettings(); };
        BuildMenu();
        Handle(activation);
        _ = updater.Check(false);
        using var timer = new System.Windows.Forms.Timer { Interval = 3600_000 };
        timer.Tick += (_, _) => _ = updater.Check(false);
        timer.Start();
        Microsoft.Win32.SystemEvents.PowerModeChanged += (_, e) =>
        {
            if (e.Mode == Microsoft.Win32.PowerModes.Resume) _ = helper.SafeRequest("sync");
        };
        try { Application.Run(); }
        finally
        {
            tray.Visible = false;
            CoRevokeClassObject(cookie);
            helper.Stop();
        }
        return 0;
    }

    private static void Handle(AppActivationArguments args)
    {
        if (args.Kind == ExtendedActivationKind.Protocol && args.Data is IProtocolActivatedEventArgs protocol)
        {
            switch (Router.Parse(protocol.Uri))
            {
                case Router.ShowSettings: ShowSettings(); break;
                case { } action: Router.Route(action); break;
            }
        }
        else if (args.Kind == ExtendedActivationKind.Launch && !helper.Status.Authenticated && Settings.Get("onboarded") == null)
        {
            Settings.Set("onboarded", "1");
            ShowSettings();
        }
    }

    private static void ShowSettings()
    {
        if (settings is { IsDisposed: false }) { settings.Activate(); return; }
        settings = new SettingsForm(helper, updater, () => { BuildMenu(); provider.RefreshAll(); });
        settings.Show();
        settings.Activate();
    }

    private static void BuildMenu()
    {
        if (tray == null) return;
        var s = helper.Status;
        var summary = !helper.Running ? L("status.stopped") : !s.Authenticated ? L("status.disconnected")
            : s.Busy ? L("status.syncing") : s.Error is { Length: > 0 } ? L("status.failed") : Plural("status.connected", s.Tasks);
        tray.Text = (L("app.name") + " — " + summary)[..Math.Min(127, L("app.name").Length + 3 + summary.Length)];
        var menu = new ContextMenuStrip();
        menu.Items.Add(new ToolStripMenuItem(summary) { Enabled = false });
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add(L("menu.open"), null, (_, _) => Router.Route(new Router.Open()));
        menu.Items.Add(L("menu.add"), null, (_, _) => Router.Route(new Router.Add()));
        menu.Items.Add(new ToolStripMenuItem(L("menu.sync"), null, (_, _) => _ = helper.SafeRequest("sync")) { Enabled = s.Authenticated });
        menu.Items.Add(new ToolStripSeparator());
        if (updater.Current == Updater.State.Available)
            menu.Items.Add(L("menu.update", updater.AvailableVersion ?? ""), null, (_, _) => _ = updater.Install());
        menu.Items.Add(L("menu.checkUpdates"), null, (_, _) => { ShowSettings(); _ = updater.Check(true); });
        menu.Items.Add(L("menu.settings"), null, (_, _) => ShowSettings());
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add(L("menu.quit"), null, (_, _) => Application.Exit());
        var old = tray.ContextMenuStrip;
        tray.ContextMenuStrip = menu;
        old?.Dispose();
    }
}

[ComImport, Guid("00000001-0000-0000-C000-000000000046"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IClassFactory
{
    [PreserveSig] int CreateInstance(IntPtr outer, ref Guid iid, out IntPtr instance);
    [PreserveSig] int LockServer([MarshalAs(UnmanagedType.Bool)] bool locked);
}

[ComVisible(true), ClassInterface(ClassInterfaceType.None)]
internal sealed class ProviderFactory(WidgetProvider provider) : IClassFactory
{
    public int CreateInstance(IntPtr outer, ref Guid iid, out IntPtr instance)
    {
        instance = IntPtr.Zero;
        if (outer != IntPtr.Zero) return unchecked((int)0x80040110);
        var inspectable = MarshalInspectable<IWidgetProvider>.FromManaged(provider);
        try { return Marshal.QueryInterface(inspectable, ref iid, out instance); }
        finally { Marshal.Release(inspectable); }
    }
    public int LockServer(bool locked) => 0;
}
