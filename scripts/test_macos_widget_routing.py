"""Exercise the production router with a fake workspace; never launch real apps."""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class WidgetRoutingTest(unittest.TestCase):
    def test_opening_order(self):
        router = (ROOT / "native/macos/Companion/Sources/Router.swift").read_text()
        shim = r'''
import Foundation
final class TestDefaults {
    static let standard = TestDefaults()
    private var values: [String: String] = [:]
    func string(forKey key: String) -> String? { values[key] }
    func set(_ value: String, forKey key: String) { values[key] = value }
}
struct Bundle {
    static var installed: [String: String] = [:]
    let bundleIdentifier: String?
    init?(url: URL) {
        guard let id = Self.installed[url.path] else { return nil }
        bundleIdentifier = id
    }
}
final class NSWorkspace {
    static let shared = NSWorkspace()
    struct OpenConfiguration {}
    var candidates: [URL] = []
    var handler: URL?
    var accepted = true
    var launchError: Error?
    var opened: [String] = []
    func urlsForApplications(withBundleIdentifier id: String) -> [URL] { candidates }
    func urlForApplication(toOpen url: URL) -> URL? { handler }
    @discardableResult func open(_ url: URL) -> Bool {
        opened.append(url.absoluteString)
        return accepted
    }
    func openApplication(at url: URL, configuration: OpenConfiguration,
                         completionHandler: (Any?, Error?) -> Void) {
        opened.append(url.path)
        completionHandler(nil, launchError)
    }
}
'''
        checks = r'''
let workspace = NSWorkspace.shared
let pake = "/Applications/Tasks.app"
let id = "task-identity-0001"
Bundle.installed[pake] = Router.pakeBundleID
workspace.handler = URL(fileURLWithPath: "/Applications/PWA.app")
Router.open(.task(id))
assert(workspace.opened == [pake], "Pake must win even when the PWA is registered")

workspace.opened = []
Bundle.installed = [:]
Router.open(.task(id))
assert(workspace.opened == ["web+vervetwodo://task?id=" + id])

workspace.opened = []
workspace.handler = nil
Router.open(.add)
assert(workspace.opened == ["https://tasks.camille.studio/?new=1&widget=1"])

workspace.opened = []
workspace.handler = URL(fileURLWithPath: "/Applications/PWA.app")
workspace.accepted = false
Router.open(.open)
assert(workspace.opened == ["web+vervetwodo://open", "https://tasks.camille.studio/?widget=1"])
workspace.accepted = true

workspace.opened = []
Bundle.installed[pake] = Router.pakeBundleID
OpenTarget.current = .web
Router.open(.add)
assert(workspace.opened == ["web+vervetwodo://add"], "Keep the explicit web preference")
OpenTarget.current = .automatic

workspace.opened = []
workspace.launchError = NSError(domain: "test", code: 1)
Router.open(.task(id))
RunLoop.main.run(until: Date().addingTimeInterval(0.05))
assert(workspace.opened == [pake, "web+vervetwodo://task?id=" + id])
workspace.launchError = nil

workspace.opened = []
Bundle.installed[pake] = "other.application"
let archived = URL(fileURLWithPath: "/tmp/archive/Tasks.app")
Bundle.installed[archived.path] = Router.pakeBundleID
workspace.candidates = [archived]
Router.open(.open)
assert(workspace.opened == ["web+vervetwodo://open"], "Ignore wrong identities and archived apps")
assert(Router.action(URL(string: "vervetwodo-widgets://task?id=invalid")!) == nil)
print("PASS: Pake/PWA/browser order, preferences, launch failures and app identity checks")
'''
        router = router.replace("import AppKit", "").replace("UserDefaults.standard", "TestDefaults.standard")
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary) / "RoutingTests.swift"
            source.write_text(shim + router + checks)
            subprocess.run(["swift", "-module-cache-path", str(ROOT / "build/widget-diagnostics/cache"),
                            str(source)], check=True)


if __name__ == "__main__":
    unittest.main()
