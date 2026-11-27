import AppKit
import CryptoKit

/// Self-update from the shared download catalogue. The build is ad-hoc signed, so trust rests
/// on HTTPS plus the catalogue's size and SHA-256; the bundle identifier, a strictly newer
/// version and a valid signature are checked before the swap.
@MainActor
final class Updater: ObservableObject {
    struct Asset: Decodable { let platform: String; let channel: String; let primary: Bool; let size: Int64; let sha256: String; let url: String }
    struct Release: Decodable { let id: String; let version: String; let assets: [Asset] }
    private struct Catalogue: Decodable { let releases: [Release] }

    enum State: Equatable {
        case idle, checking, upToDate, downloading
        case available(String)
        case failed(String)
    }

    @Published private(set) var state = State.idle
    private var release: (Release, Asset)?

    static let server = "https://download.camille.studio"
    static let catalogue = URL(string: server + "/api/v1/vervetwodo-widgets.json")!
    static var current: String { Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0" }

    static func newer(_ a: String, than b: String) -> Bool {
        let x = a.split(separator: ".").map { Int($0) ?? 0 }, y = b.split(separator: ".").map { Int($0) ?? 0 }
        for i in 0..<max(x.count, y.count) where (i < x.count ? x[i] : 0) != (i < y.count ? y[i] : 0) {
            return (i < x.count ? x[i] : 0) > (i < y.count ? y[i] : 0)
        }
        return false
    }

    /// Automatic checks run at most daily and respect Later; manual checks bypass both.
    func check(manual: Bool) {
        let defaults = UserDefaults.standard
        let now = Date().timeIntervalSince1970
        if !manual && now - defaults.double(forKey: "updateCheckedAt") < 24 * 3600 { return }
        if state == .checking || state == .downloading { return }
        state = .checking
        Task {
            do {
                var request = URLRequest(url: Self.catalogue)
                request.cachePolicy = .reloadIgnoringLocalCacheData
                let (data, response) = try await URLSession.shared.data(for: request)
                guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw URLError(.badServerResponse) }
                let catalogue = try JSONDecoder().decode(Catalogue.self, from: data)
                defaults.set(now, forKey: "updateCheckedAt")
                let candidates = catalogue.releases.compactMap { release in
                    release.assets.first { $0.platform == "macos" && $0.primary && $0.channel == "release" }.map { (release, $0) }
                }
                guard let best = candidates.max(by: { Self.newer($1.0.version, than: $0.0.version) }),
                      Self.newer(best.0.version, than: Self.current) else {
                    release = nil
                    state = .upToDate
                    return
                }
                if !manual, defaults.string(forKey: "updateSnoozedVersion") == best.0.version,
                   now < defaults.double(forKey: "updateSnoozedUntil") {
                    state = .idle
                    return
                }
                release = best
                state = .available(best.0.version)
            } catch {
                state = .failed(L("update.failed.check"))
            }
        }
    }

    func later() {
        guard case .available(let version) = state else { return }
        UserDefaults.standard.set(version, forKey: "updateSnoozedVersion")
        UserDefaults.standard.set(Date().timeIntervalSince1970 + 24 * 3600, forKey: "updateSnoozedUntil")
        state = .idle
    }

    func install() {
        guard let (release, asset) = release, let source = URL(string: Self.server + asset.url) else { return }
        state = .downloading
        Task {
            do {
                let staging = try prepare(try await download(source, asset), version: release.version)
                try swap(to: staging)
            } catch {
                state = .failed((error as? LocalizedError)?.errorDescription ?? L("update.failed.install"))
            }
        }
    }

    private struct Rejected: LocalizedError { var errorDescription: String? { L("update.failed.verify") } }

    private func download(_ source: URL, _ asset: Asset) async throws -> URL {
        let (temporary, response) = try await URLSession.shared.download(from: source)
        guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw URLError(.badServerResponse) }
        let handle = try FileHandle(forReadingFrom: temporary)
        defer { try? handle.close() }
        var hash = SHA256(), size: Int64 = 0
        while let chunk = try handle.read(upToCount: 1 << 20), !chunk.isEmpty {
            hash.update(data: chunk)
            size += Int64(chunk.count)
        }
        let digest = hash.finalize().map { String(format: "%02x", $0) }.joined()
        guard size == asset.size, digest == asset.sha256.lowercased() else { throw Rejected() }
        return temporary
    }

    private func run(_ path: String, _ arguments: [String]) throws {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: path)
        process.arguments = arguments
        process.standardOutput = FileHandle.nullDevice
        process.standardError = FileHandle.nullDevice
        try process.run()
        process.waitUntilExit()
        guard process.terminationStatus == 0 else { throw Rejected() }
    }

    private func prepare(_ archive: URL, version: String) throws -> URL {
        let fm = FileManager.default
        let staging = Helper.dataDirectory.appendingPathComponent("Update", isDirectory: true)
        try? fm.removeItem(at: staging)
        try fm.createDirectory(at: staging, withIntermediateDirectories: true)
        try run("/usr/bin/ditto", ["-x", "-k", archive.path, staging.path])
        let app = staging.appendingPathComponent(Bundle.main.bundleURL.lastPathComponent)
        guard let bundle = Bundle(url: app), bundle.bundleIdentifier == Bundle.main.bundleIdentifier,
              let found = bundle.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String,
              found == version, Self.newer(found, than: Self.current) else { throw Rejected() }
        try run("/usr/bin/codesign", ["--verify", "--deep", "--strict", app.path])
        return app
    }

    /// A detached shell waits for this process to exit, swaps the bundle with a rollback
    /// copy, re-registers the widget extension and relaunches.
    private func swap(to staged: URL) throws {
        let script = """
        while kill -0 "$1" 2>/dev/null; do sleep 0.2; done
        rm -rf "$3"
        mv "$2" "$3" || exit 1
        if /usr/bin/ditto "$4" "$2"; then
          rm -rf "$3" "$(dirname "$4")"
        else
          rm -rf "$2"; mv "$3" "$2"
        fi
        /usr/bin/pluginkit -a "$2/Contents/PlugIns/VerveWidgets.appex" 2>/dev/null
        /usr/bin/open "$2"
        """
        let current = Bundle.main.bundleURL
        let backup = current.deletingLastPathComponent().appendingPathComponent("." + current.lastPathComponent + ".previous")
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/bin/sh")
        process.arguments = ["-c", script, "sh", String(ProcessInfo.processInfo.processIdentifier), current.path, backup.path, staged.path]
        try process.run()
        NSApp.terminate(nil)
    }
}
