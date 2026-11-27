using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Diagnostics;
using System.Drawing;
using System.Globalization;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Threading.Tasks;
using System.Windows.Forms;
using Microsoft.Win32;

namespace Setup;

/// One-step installer for VerveTwoDo Widgets. The MSIX is self-signed, so Windows installs it only
/// once its certificate is trusted; that single step relaunches this program elevated. Everything
/// else, including the package itself, installs for the current user.
internal static class Program
{
    private const string PackageName = "studio.camille.vervetwodo.widgets";

    [STAThread]
    private static int Main(string[] args)
    {
        try
        {
            // Elevated child: trust only the certificate embedded in this program.
            if (args.Length == 1 && args[0] == "--trust") return Trust();
            // Used by the installed companion to update itself without any prompt.
            if (args.Length == 2 && args[0] == "--extract") { Extract(args[1]); return 0; }
        }
        catch (Exception)
        {
            return 1;
        }
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);
        Application.Run(new SetupForm());
        return 0;
    }

    internal static Stream Payload() =>
        typeof(Program).Assembly.GetManifestResourceStream("payload.zip") ?? throw new InvalidDataException("payload missing");

    internal static void Extract(string directory)
    {
        Directory.CreateDirectory(directory);
        using var archive = new ZipArchive(Payload(), ZipArchiveMode.Read);
        foreach (var entry in archive.Entries)
        {
            if (entry.FullName.Contains("/") || entry.FullName.Contains("\\") || entry.FullName.Contains("..")) throw new InvalidDataException(entry.FullName);
            entry.ExtractToFile(Path.Combine(directory, entry.FullName), true);
        }
    }

    internal static X509Certificate2 Certificate()
    {
        using var archive = new ZipArchive(Payload(), ZipArchiveMode.Read);
        var entry = archive.Entries.Single(e => e.FullName.EndsWith(".cer", StringComparison.OrdinalIgnoreCase));
        using var stream = entry.Open();
        using var buffer = new MemoryStream();
        stream.CopyTo(buffer);
        return new X509Certificate2(buffer.ToArray());
    }

    internal static bool Trusted(X509Certificate2 certificate)
    {
        using var store = new X509Store(StoreName.TrustedPeople, StoreLocation.LocalMachine);
        store.Open(OpenFlags.ReadOnly | OpenFlags.OpenExistingOnly);
        return store.Certificates.Find(X509FindType.FindByThumbprint, certificate.Thumbprint, false).Count > 0;
    }

    private static int Trust()
    {
        var certificate = Certificate();
        using var store = new X509Store(StoreName.TrustedPeople, StoreLocation.LocalMachine);
        store.Open(OpenFlags.ReadWrite);
        store.Add(certificate);
        return 0;
    }

    internal static bool Supported()
    {
        using var key = Registry.LocalMachine.OpenSubKey(@"SOFTWARE\Microsoft\Windows NT\CurrentVersion");
        return int.TryParse(key?.GetValue("CurrentBuildNumber") as string, out var build) && build >= 22000;
    }

    /// Installs the package for the current user and opens the companion.
    internal static void Install(string package)
    {
        var script = "$ErrorActionPreference = 'Stop'\n" +
                     "try {\n" +
                     $"  Add-AppxPackage -Path '{package.Replace("'", "''")}' -ForceApplicationShutdown\n" +
                     $"  $p = Get-AppxPackage -Name '{PackageName}'\n" +
                     "  if ($p) { Start-Process ('shell:AppsFolder\\' + $p.PackageFamilyName + '!App') }\n" +
                     "} catch { [Console]::Error.WriteLine($_.Exception.Message); exit 1 }";
        var shell = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.System), @"WindowsPowerShell\v1.0\powershell.exe");
        var start = new ProcessStartInfo(shell,
            "-NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand " + Convert.ToBase64String(Encoding.Unicode.GetBytes(script)))
        {
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardError = true,
            RedirectStandardOutput = true,
        };
        using var process = Process.Start(start)!;
        var output = process.StandardOutput.ReadToEndAsync();
        var error = process.StandardError.ReadToEnd();
        process.WaitForExit();
        _ = output.Result;
        if (process.ExitCode != 0) throw new InstallException(error.Trim());
    }
}

internal sealed class InstallException(string message) : Exception(message);

internal static class Strings
{
    private static readonly Dictionary<string, string> Values = Load(
        CultureInfo.CurrentUICulture.TwoLetterISOLanguageName == "fr" ? "fr" : "en");

    private static Dictionary<string, string> Load(string language)
    {
        using var stream = typeof(Strings).Assembly.GetManifestResourceStream($"Setup.Strings.{language}.txt")!;
        using var reader = new StreamReader(stream, Encoding.UTF8);
        return reader.ReadToEnd().Split('\n')
            .Select(line => line.TrimEnd('\r'))
            .Where(line => line.Contains("="))
            .ToDictionary(line => line.Substring(0, line.IndexOf('=')), line => line.Substring(line.IndexOf('=') + 1));
    }

    public static string L(string key, params object[] args) =>
        Values.TryGetValue(key, out var value) ? string.Format(value, args) : key;
}

internal sealed class SetupForm : Form
{
    private readonly Label status = new() { Dock = DockStyle.Fill, TextAlign = ContentAlignment.MiddleLeft };
    private readonly ProgressBar progress = new() { Dock = DockStyle.Bottom, Style = ProgressBarStyle.Marquee, Height = 12 };
    private readonly Button close = new() { Text = Strings.L("close"), AutoSize = true, Visible = false, Anchor = AnchorStyles.Right };

    public SetupForm()
    {
        Text = Strings.L("title");
        Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath);
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = MinimizeBox = false;
        StartPosition = FormStartPosition.CenterScreen;
        AutoScaleMode = AutoScaleMode.Dpi;
        ClientSize = new Size(480, 170);
        Padding = new Padding(20);
        Font = SystemFonts.MessageBoxFont;
        var buttons = new FlowLayoutPanel { Dock = DockStyle.Bottom, FlowDirection = FlowDirection.RightToLeft, AutoSize = true };
        buttons.Controls.Add(close);
        close.Click += (_, _) => Close();
        AcceptButton = close;
        Controls.Add(status);
        Controls.Add(progress);
        Controls.Add(buttons);
        Shown += async (_, _) => await Run();
    }

    private void Report(string text) => status.Text = text;

    private void Finish(string text)
    {
        Report(text);
        progress.Visible = false;
        close.Visible = true;
        close.Focus();
    }

    private async Task Run()
    {
        Report(Strings.L("preparing"));
        if (!Program.Supported()) { Finish(Strings.L("unsupported")); return; }
        var staging = Path.Combine(Path.GetTempPath(), "VerveTwoDoWidgets-Setup-" + Guid.NewGuid().ToString("N"));
        try
        {
            await Task.Run(() => Program.Extract(staging));
            var certificate = Program.Certificate();
            if (!Program.Trusted(certificate))
            {
                Report(Strings.L("permission"));
                var elevated = new ProcessStartInfo(Application.ExecutablePath, "--trust") { UseShellExecute = true, Verb = "runas" };
                try
                {
                    using var process = Process.Start(elevated)!;
                    await Task.Run(process.WaitForExit);
                }
                catch (Win32Exception error) when (error.NativeErrorCode == 1223)
                {
                    Finish(Strings.L("declined"));
                    return;
                }
                if (!Program.Trusted(certificate)) { Finish(Strings.L("declined")); return; }
            }
            Report(Strings.L("installing"));
            var package = Directory.GetFiles(staging, "*.msix").Single();
            await Task.Run(() => Program.Install(package));
            Finish(Strings.L("done"));
        }
        catch (Exception error)
        {
            Finish(Strings.L("failed") + "\n\n" + Strings.L("details", error.Message));
        }
        finally
        {
            try { Directory.Delete(staging, true); } catch (IOException) { } catch (UnauthorizedAccessException) { }
        }
    }
}
