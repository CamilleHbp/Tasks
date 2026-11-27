using System.Globalization;
using static Companion.Localization;

namespace Companion;

internal sealed class SettingsForm : Form
{
    private readonly Helper helper;
    private readonly Updater updater;
    private readonly Action relocalize;
    private readonly FlowLayoutPanel content = new() { FlowDirection = FlowDirection.TopDown, AutoSize = true, AutoSizeMode = AutoSizeMode.GrowAndShrink, Padding = new Padding(16), WrapContents = false };

    public SettingsForm(Helper helper, Updater updater, Action relocalize)
    {
        this.helper = helper;
        this.updater = updater;
        this.relocalize = relocalize;
        AutoScaleMode = AutoScaleMode.Dpi;
        AutoSize = true;
        AutoSizeMode = AutoSizeMode.GrowAndShrink;
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = MinimizeBox = false;
        StartPosition = FormStartPosition.CenterScreen;
        Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath);
        Controls.Add(content);
        helper.Changed += Refresh;
        updater.Changed += Refresh;
        FormClosed += (_, _) => { helper.Changed -= Refresh; updater.Changed -= Refresh; };
        Build();
    }

    private new void Refresh()
    {
        if (IsDisposed) return;
        if (InvokeRequired) { BeginInvoke(Refresh); return; }
        // Keep typed connection details while rebuilding status rows.
        if (formAuthenticated == helper.Status.Authenticated && formOrigin == helper.Status.Origin && content.Controls.Find("origin", true).FirstOrDefault() is TextBox) { UpdateStatusLabels(); return; }
        Build();
    }

    private string connectionMessage = "";
    private bool formAuthenticated;
    private string? formOrigin;
    private Label status = new(), update = new();

    private static Label Heading(string text) => new() { Text = text, AutoSize = true, Font = new Font(SystemFonts.MessageBoxFont!, FontStyle.Bold), Margin = new Padding(0, 12, 0, 4) };
    private static Label Note(string text) => new() { Text = text, AutoSize = true, MaximumSize = new Size(420, 0), ForeColor = SystemColors.GrayText };
    private static FlowLayoutPanel Row(params Control[] controls)
    {
        var row = new FlowLayoutPanel { AutoSize = true, WrapContents = false, Margin = new Padding(0, 4, 0, 0) };
        row.Controls.AddRange(controls);
        return row;
    }
    private static Button Button(string text, EventHandler click) { var b = new Button { Text = text, AutoSize = true }; b.Click += click; return b; }

    private void Build()
    {
        Text = L("app.name");
        content.SuspendLayout();
        content.Controls.Clear();
        formAuthenticated = helper.Status.Authenticated;
        formOrigin = helper.Status.Origin;
        content.Controls.Add(Heading(L("settings.workspace")));
        status = Note("");
        if (helper.Status.Authenticated)
        {
            content.Controls.Add(new Label { Text = L("settings.connectedTo", helper.Status.Origin), AutoSize = true, MaximumSize = new Size(420, 0) });
            content.Controls.Add(status);
            content.Controls.Add(Row(Button(L("settings.syncNow"), async (_, _) => await helper.SafeRequest("sync")),
                Button(L("settings.disconnect"), async (_, _) => await Run(() => helper.Request("disconnect")))));
        }
        {
            if (!helper.Status.Authenticated) content.Controls.Add(Note(L("settings.connectHint")));
            var origin = new TextBox { Name = "origin", Text = string.IsNullOrEmpty(helper.Status.Origin) ? Router.WebOrigin : helper.Status.Origin, Width = 320, PlaceholderText = "https://", AccessibleName = L("settings.origin") };
            var code = new TextBox { PlaceholderText = helper.Status.Authenticated ? "********" : "", Width = 320, UseSystemPasswordChar = true, AccessibleName = L("settings.code") };
            origin.TextChanged += (_, _) => { code.Clear(); code.PlaceholderText = helper.Status.Authenticated && origin.Text == helper.Status.Origin ? "********" : ""; };
            var feedback = Note("");
            content.Controls.Add(new Label { Text = L("settings.origin"), AutoSize = true });
            content.Controls.Add(origin);
            content.Controls.Add(new Label { Text = L("settings.code"), AutoSize = true });
            content.Controls.Add(code);
            var connect = Button(L("settings.connect"), async (_, _) =>
            {
                try {
                    await helper.Request("connect", new() { ["origin"] = origin.Text.Trim(), ["code"] = code.Text.Trim() });
                    code.Clear();
                    connectionMessage = L("settings.success");
                } catch (HelperException error) { connectionMessage = error.Message; }
                if (content.Controls.Find("feedback", true).FirstOrDefault() is Label result) result.Text = connectionMessage;
            });
            feedback.Name = "feedback"; feedback.Text = connectionMessage;
            connect.Enabled = false;
            code.TextChanged += (_, _) => connect.Enabled = origin.Text.Trim().Length > 0 && code.Text.Trim().Length > 0;
            AcceptButton = connect;
            content.Controls.Add(Row(connect));
            content.Controls.Add(feedback);
            content.Controls.Add(status);
        }

        content.Controls.Add(Heading(L("settings.preferences")));
        var language = new ComboBox { DropDownStyle = ComboBoxStyle.DropDownList, Width = 220, AccessibleName = L("settings.language") };
        string[] languages = ["system", "en", "fr"];
        language.Items.AddRange([L("settings.language.system"), "English", "Français"]);
        language.SelectedIndex = Array.IndexOf(languages, Localization.Language);
        language.SelectedIndexChanged += async (_, _) =>
        {
            Localization.Language = languages[language.SelectedIndex];
            await helper.SafeRequest("locale", new() { ["locale"] = Resolved });
            relocalize();
            Build();
        };
        content.Controls.Add(Row(new Label { Text = L("settings.language"), AutoSize = true, Width = 160, Anchor = AnchorStyles.Left }, language));
        var target = new ComboBox { DropDownStyle = ComboBoxStyle.DropDownList, Width = 220, AccessibleName = L("settings.openIn") };
        string[] targets = ["automatic", "desktop", "web"];
        target.Items.AddRange([L("settings.openIn.automatic"), L("settings.openIn.desktop"), L("settings.openIn.web")]);
        target.SelectedIndex = Array.IndexOf(targets, Router.Target);
        target.SelectedIndexChanged += (_, _) => Router.Target = targets[target.SelectedIndex];
        content.Controls.Add(Row(new Label { Text = L("settings.openIn"), AutoSize = true, Width = 160, Anchor = AnchorStyles.Left }, target));

        content.Controls.Add(Heading(L("settings.about")));
        content.Controls.Add(new Label { Text = L("settings.version", Updater.Version), AutoSize = true });
        update = Note("");
        var buttons = Row(Button(L("settings.checkUpdates"), async (_, _) => await updater.Check(true)));
        if (updater.Current == Updater.State.Available)
        {
            buttons.Controls.Add(Button(L("update.install"), async (_, _) => await updater.Install()));
            buttons.Controls.Add(Button(L("update.later"), (_, _) => updater.Later()));
        }
        content.Controls.Add(buttons);
        content.Controls.Add(update);
        UpdateStatusLabels();
        content.ResumeLayout();
    }

    private void UpdateStatusLabels()
    {
        var s = helper.Status;
        status.Text = !helper.Running ? L("status.stopped") : !s.Authenticated ? (s.Error is { Length: > 0 } ? status.Text : "")
            : s.Busy ? L("status.syncing")
            : s.LastSuccessAt > 0 ? L("settings.lastSync", DateTimeOffset.FromUnixTimeMilliseconds(s.LastSuccessAt).LocalDateTime.ToString("g", CultureInfo.GetCultureInfo(Resolved)))
            : "";
        update.Text = updater.Current switch
        {
            Updater.State.Checking => L("update.checking"),
            Updater.State.Downloading => L("update.downloading"),
            Updater.State.UpToDate => L("update.upToDate"),
            Updater.State.Available => L("update.available", updater.AvailableVersion ?? ""),
            Updater.State.Failed => updater.Failure ?? "",
            _ => "",
        };
    }

    private async Task Run(Func<Task> action)
    {
        UseWaitCursor = true;
        try { await action(); }
        catch (HelperException error) { status.Text = error.Message; status.ForeColor = Color.Firebrick; }
        finally { UseWaitCursor = false; }
    }
}
