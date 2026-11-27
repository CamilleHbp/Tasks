import AppKit
import ServiceManagement
import SwiftUI

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {
    let helper = Helper()
    let updater = Updater()
    private var timer: Timer?

    func applicationDidFinishLaunching(_ notification: Notification) {
        if Mover.moveIfNeeded() { return }
        helper.start()
        updater.check(manual: false)
        timer = Timer.scheduledTimer(withTimeInterval: 3600, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.updater.check(manual: false) }
        }
        NSWorkspace.shared.notificationCenter.addObserver(forName: NSWorkspace.didWakeNotification, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in _ = try? await self?.helper.request("sync") }
        }
        Task {
            guard UserDefaults.standard.object(forKey: "onboarded") == nil,
                  let status = try? await helper.request("status"), !status.authenticated else { return }
            UserDefaults.standard.set(true, forKey: "onboarded")
            showSettings()
        }
    }

    func applicationWillTerminate(_ notification: Notification) { helper.stop() }

    func application(_ application: NSApplication, open urls: [URL]) {
        for url in urls {
            switch Router.action(url) {
            case .settings: showSettings()
            // Waiting widget taps need a connected helper; ask to connect rather than sync nothing.
            case .sync:
                helper.start()
                showSettings()
                Task {
                    if (try? await helper.request("status"))?.authenticated == true { _ = try? await helper.request("sync") }
                }
            case .some(let action): Router.open(action)
            case nil: break
            }
        }
    }

    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        showSettings()
        return true
    }

    private var window: NSWindow?

    func showSettings() {
        if window == nil {
            let window = NSWindow(contentViewController: NSHostingController(rootView: SettingsView().environmentObject(helper).environmentObject(updater)))
            window.title = L("settings.title")
            window.styleMask = [.titled, .closable]
            window.isReleasedWhenClosed = false
            self.window = window
        }
        window?.title = L("settings.title")
        NSApp.activate(ignoringOtherApps: true)
        window?.center()
        window?.makeKeyAndOrderFront(nil)
    }
}

@main
struct CompanionApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) var delegate

    var body: some Scene {
        MenuBarExtra {
            MenuContent(open: delegate.showSettings).environmentObject(delegate.helper).environmentObject(delegate.updater)
        } label: {
            MenuIcon().environmentObject(delegate.updater)
        }
    }
}

struct MenuIcon: View {
    @EnvironmentObject var updater: Updater
    var body: some View {
        if case .available = updater.state {
            Image(systemName: "checklist.unchecked").symbolRenderingMode(.multicolor).overlay(alignment: .topTrailing) {
                Circle().fill(.red).frame(width: 5, height: 5)
            }.accessibilityLabel(L("menu.updateAvailableIcon"))
        } else {
            Image(systemName: "checklist").accessibilityLabel(L("app.name"))
        }
    }
}

struct MenuContent: View {
    @EnvironmentObject var helper: Helper
    @EnvironmentObject var updater: Updater
    let open: () -> Void

    var body: some View {
        Text(summary)
        Divider()
        Button(L("menu.open")) { Router.open(.open) }
        Button(L("menu.add")) { Router.open(.add) }
        Button(L(helper.status.authenticated ? "menu.sync" : "settings.connect")) {
            open()
            if helper.status.authenticated { Task { _ = try? await helper.request("sync") } }
        }
        Divider()
        if case .available(let version) = updater.state {
            Button(LF("menu.update", version)) { updater.install() }
        }
        Button(L("menu.checkUpdates")) { updater.check(manual: true); open() }
        Button(L("menu.settings")) { open() }.keyboardShortcut(",")
        Divider()
        Button(L("menu.quit")) { NSApp.terminate(nil) }.keyboardShortcut("q")
    }

    private var summary: String {
        let s = helper.status
        if !helper.running { return L("status.stopped") }
        if !s.authenticated { return L("status.disconnected") }
        if s.busy { return L("status.syncing") }
        if let error = s.error, !error.isEmpty { return L("status.failed") }
        if !s.confirmed { return L("status.unconfirmed") }
        return LF("status.connected", s.tasks)
    }
}

struct SettingsView: View {
    @EnvironmentObject var helper: Helper
    @EnvironmentObject var updater: Updater
    @State private var origin = Router.webOrigin
    @State private var code = ""
    @State private var confirmDisconnect = false
    @State private var working = false
    @State private var message: String?
    @State private var language = Localization.language
    @State private var target = OpenTarget.current
    @State private var login = SMAppService.mainApp.status == .enabled
    @State private var refresh = 0

    var body: some View {
        Form {
            Section(L("settings.workspace")) {
                if !helper.receivedStatus {
                    Text(L("status.stopped"))
                } else if helper.status.authenticated {
                    LabeledContent(L("settings.connectedTo"), value: helper.status.origin)
                    if helper.status.lastSuccessAt > 0 {
                        LabeledContent(L("settings.lastSync"), value: Date(timeIntervalSince1970: Double(helper.status.lastSuccessAt) / 1000)
                            .formatted(Date.FormatStyle(date: .abbreviated, time: .shortened).locale(Locale(identifier: Localization.resolved))))
                    }
                    if helper.status.busy { ProgressView(L("status.syncing")).controlSize(.small) }
                    else if let error = helper.status.error { Text(HelperFailure(code: error).localizedDescription).foregroundStyle(.red) }
                    else if !helper.status.confirmed { Text(L("status.unconfirmed")) }
                    HStack {
                        Button(L("settings.syncNow")) { run { _ = try await helper.request("sync") } }.disabled(helper.status.busy || working)
                        Button(L("settings.disconnect"), role: .destructive) { confirmDisconnect = true }
                            .confirmationDialog(L("settings.confirmDisconnect"), isPresented: $confirmDisconnect) {
                                Button(L("settings.disconnect"), role: .destructive) { run { _ = try await helper.request("disconnect") } }
                            }
                    }
                } else {
                    Text(L("settings.connectHint")).font(.callout).foregroundStyle(.secondary)
                    TextField(L("settings.origin"), text: $origin, prompt: Text(verbatim: "https://"))
                    SecureField(L("settings.code"), text: $code)
                    Button(L("settings.connect")) {
                        let origin = origin, code = code
                        run {
                            try await helper.request("connect", ["origin": origin, "code": code])
                            self.code = ""
                        }
                    }.disabled(origin.isEmpty || code.isEmpty || working).keyboardShortcut(.defaultAction)
                }
                if let message { Text(message).font(.callout).foregroundStyle(.red) }
            }
            Section(L("settings.preferences")) {
                Picker(L("settings.language"), selection: $language) {
                    Text(L("settings.language.system")).tag(Language.system)
                    Text(verbatim: "English").tag(Language.en)
                    Text(verbatim: "Français").tag(Language.fr)
                }.onChange(of: language) { _, value in
                    Localization.language = value
                    helper.setLocale()
                    refresh += 1
                }
                Picker(L("settings.openIn"), selection: $target) {
                    Text(L("settings.openIn.automatic")).tag(OpenTarget.automatic)
                    Text(L("settings.openIn.desktop")).tag(OpenTarget.desktop)
                    Text(L("settings.openIn.web")).tag(OpenTarget.web)
                }.onChange(of: target) { _, value in OpenTarget.current = value }
                Toggle(L("settings.login"), isOn: $login).onChange(of: login) { _, value in
                    do { if value { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() } }
                    catch { login = SMAppService.mainApp.status == .enabled }
                }
            }
            Section(L("settings.about")) {
                LabeledContent(L("settings.version"), value: Updater.current)
                HStack {
                    Button(L("settings.checkUpdates")) { updater.check(manual: true) }.disabled(updater.state == .checking || updater.state == .downloading)
                    updateStatus
                }
            }
        }
        .formStyle(.grouped)
        .frame(width: 440)
        .fixedSize(horizontal: false, vertical: true)
        .id(refresh)
    }

    @ViewBuilder private var updateStatus: some View {
        switch updater.state {
        case .checking: ProgressView().controlSize(.small)
        case .downloading: Text(L("update.downloading")).foregroundStyle(.secondary)
        case .upToDate: Text(L("update.upToDate")).foregroundStyle(.secondary)
        case .failed(let reason): Text(reason).foregroundStyle(.red)
        case .available(let version):
            Text(LF("update.available", version))
            Button(L("update.install")) { updater.install() }
            Button(L("update.later")) { updater.later() }
        case .idle: EmptyView()
        }
    }

    private func run(_ action: @escaping () async throws -> Void) {
        working = true
        message = nil
        Task {
            do { try await action() } catch { message = error.localizedDescription }
            working = false
        }
    }
}
