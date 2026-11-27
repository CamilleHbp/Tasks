using System.Runtime.InteropServices;
using System.Text.Json;
using Microsoft.Windows.AppNotifications;
using Microsoft.Windows.AppNotifications.Builder;
using Microsoft.Windows.Widgets.Providers;
using WinRT;

namespace VerveWidgets;

internal static class Program
{
    [DllImport("ole32.dll")] private static extern int CoInitializeEx(IntPtr reserved, uint flags);
    [DllImport("ole32.dll")] private static extern int CoRegisterClassObject(ref Guid clsid, [MarshalAs(UnmanagedType.IUnknown)] object factory, uint context, uint flags, out uint cookie);
    [DllImport("ole32.dll")] private static extern int CoRevokeClassObject(uint cookie);
    [DllImport("ole32.dll")] private static extern void CoUninitialize();

    [MTAThread]
    private static int Main(string[] args)
    {
        try
        {
            if (args.FirstOrDefault() == "notify")
            {
                // The JVM sends UTF-8 JSON even when Windows uses a legacy console code page.
                Console.InputEncoding = System.Text.Encoding.UTF8;
                using var payload = JsonDocument.Parse(Console.In.ReadToEnd());
                var notification = new AppNotificationBuilder().AddText(payload.RootElement.GetProperty("title").GetString() ?? "VerveTwoDo")
                    .AddText(payload.RootElement.GetProperty("body").GetString() ?? "").BuildNotification();
                var manager = AppNotificationManager.Default;
                manager.Register();
                try { manager.Show(notification); } finally { manager.Unregister(); }
                return 0;
            }
            Marshal.ThrowExceptionForHR(CoInitializeEx(IntPtr.Zero, 0));
            var clsid = typeof(WidgetProvider).GUID;
            Marshal.ThrowExceptionForHR(CoRegisterClassObject(ref clsid, new ProviderFactory(), 4, 1, out var cookie));
            try { using var wait = new ManualResetEvent(false); wait.WaitOne(); }
            finally { CoRevokeClassObject(cookie); CoUninitialize(); }
            return 0;
        }
        catch (Exception error) { Console.Error.WriteLine(error.Message); return 1; }
    }
}

[ComImport, Guid("00000001-0000-0000-C000-000000000046"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IClassFactory
{
    [PreserveSig] int CreateInstance(IntPtr outer, ref Guid iid, out IntPtr instance);
    [PreserveSig] int LockServer([MarshalAs(UnmanagedType.Bool)] bool locked);
}

[ComVisible(true), ClassInterface(ClassInterfaceType.None)]
internal sealed class ProviderFactory : IClassFactory
{
    private readonly WidgetProvider provider = new();
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
