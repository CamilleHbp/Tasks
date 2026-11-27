import AppKit

enum OpenTarget: String, CaseIterable, Identifiable {
    case automatic, desktop, web
    var id: String { rawValue }
    static var current: OpenTarget {
        get { OpenTarget(rawValue: UserDefaults.standard.string(forKey: "openTarget") ?? "") ?? .automatic }
        set { UserDefaults.standard.set(newValue.rawValue, forKey: "openTarget") }
    }
}

/// Opens the Pake wrapper, then the installed PWA, then the default browser.
enum Router {
    static let pakeBundleID = "studio.camille.pake.vervetwodo"
    static let webOrigin = "https://tasks.camille.studio"

    enum Action: Equatable { case open, add, task(String), settings, sync }

    static func action(_ url: URL) -> Action? {
        guard url.scheme == "vervetwodo-widgets", url.absoluteString.count <= 4096 else { return nil }
        switch url.host {
        case "open": return .open
        case "add": return .add
        case "settings": return .settings
        // Opened by the widget when its taps wait; launching the companion starts the helper.
        case "sync": return .sync
        case "task":
            let id = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == "id" }?.value ?? ""
            guard (16...128).contains(id.count), id.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-") }) else { return nil }
            return .task(id)
        default: return nil
        }
    }

    static func open(_ action: Action) {
        let target = OpenTarget.current
        if target != .web, let pake = installedPakeApp() {
            // Pake has no URL/deep-link handler. Launch its existing webview/session rather
            // than sending the website URL to a browser or an unsupported custom scheme.
            NSWorkspace.shared.openApplication(at: pake, configuration: NSWorkspace.OpenConfiguration()) { _, error in
                if error != nil {
                    DispatchQueue.main.async { openWeb(action) }
                }
            }
            return
        }
        openWeb(action)
    }

    private static func openWeb(_ action: Action) {
        // The installed web app declares this protocol handler; the browser registers it with
        // Launch Services, which then hands these links to that app.
        let link = webLink(action)
        if NSWorkspace.shared.urlForApplication(toOpen: link) != nil, NSWorkspace.shared.open(link) {
            return
        }
        NSWorkspace.shared.open(websiteLink(action))
    }

    static func webLink(_ action: Action) -> URL {
        switch action {
        case .task(let id): return URL(string: "web+vervetwodo://task?id=" + id)!
        case .add: return URL(string: "web+vervetwodo://add")!
        default: return URL(string: "web+vervetwodo://open")!
        }
    }

    static func websiteLink(_ action: Action) -> URL {
        var web = URLComponents(string: webOrigin + "/")!
        switch action {
        case .task(let id): web.queryItems = [URLQueryItem(name: "task", value: id), URLQueryItem(name: "widget", value: "1")]
        case .add: web.queryItems = [URLQueryItem(name: "new", value: "1"), URLQueryItem(name: "widget", value: "1")]
        default: web.queryItems = [URLQueryItem(name: "widget", value: "1")]
        }
        return web.url!
    }

    /// Launch Services also knows build outputs and archived copies; only an app in an
    /// Applications folder counts as installed.
    static func installedPakeApp() -> URL? {
        let folders = [URL(fileURLWithPath: "/Applications", isDirectory: true),
                       FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Applications", isDirectory: true)]
        let prefixes = folders.map { $0.standardizedFileURL.path + "/" }
        // Also check standard paths: Launch Services can lag behind a newly installed app.
        let candidates = NSWorkspace.shared.urlsForApplications(withBundleIdentifier: pakeBundleID)
            + folders.map { $0.appendingPathComponent("Tasks.app", isDirectory: true) }
        return candidates.first { url in
            prefixes.contains { url.standardizedFileURL.path.hasPrefix($0) }
                && Bundle(url: url)?.bundleIdentifier == pakeBundleID
        }
    }
}
