import AppKit
import Darwin

/// Moves a freshly downloaded copy into Applications on first launch, so installing from the
/// browser needs no manual move. Widgets register only from a stable location, and macOS runs
/// unmoved downloads from a read-only translocated copy.
enum Mover {
    /// Returns true when a moved copy has been launched and this one should quit.
    @MainActor static func moveIfNeeded() -> Bool {
        let current = Bundle.main.bundleURL.standardizedFileURL
        let original = originalURL(current) ?? current
        let fm = FileManager.default
        let downloads = fm.urls(for: .downloadsDirectory, in: .userDomainMask).first?.standardizedFileURL.path ?? "/"
        // Only downloaded copies move; development builds and installed copies stay where they are.
        guard original != current || original.path.hasPrefix(downloads + "/") else { return false }
        let system = URL(fileURLWithPath: "/Applications").appendingPathComponent(current.lastPathComponent)
        let folder = original.deletingLastPathComponent().path
        if folder == "/Applications" || folder == fm.homeDirectoryForCurrentUser.appendingPathComponent("Applications").standardizedFileURL.path {
            return false
        }
        let target = fm.fileExists(atPath: system.path) && fm.isWritableFile(atPath: "/Applications")
            ? system
            : fm.homeDirectoryForCurrentUser.appendingPathComponent("Applications/" + current.lastPathComponent)
        do {
            try fm.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
            for app in NSRunningApplication.runningApplications(withBundleIdentifier: Bundle.main.bundleIdentifier ?? "")
            where app.processIdentifier != getpid() { app.forceTerminate() }
            if fm.fileExists(atPath: target.path) { try fm.trashItem(at: target, resultingItemURL: nil) }
            try fm.copyItem(at: current, to: target)
        } catch {
            return false
        }
        // The user already approved this download; the installed copy should not ask again.
        run("/usr/bin/xattr", ["-dr", "com.apple.quarantine", target.path])
        for plugin in (try? fm.contentsOfDirectory(at: target.appendingPathComponent("Contents/PlugIns"), includingPropertiesForKeys: nil)) ?? [] {
            run("/usr/bin/pluginkit", ["-a", plugin.path])
        }
        try? fm.trashItem(at: original, resultingItemURL: nil)
        let configuration = NSWorkspace.OpenConfiguration()
        configuration.createsNewApplicationInstance = true
        NSWorkspace.shared.openApplication(at: target, configuration: configuration) { _, _ in
            DispatchQueue.main.async { NSApp.terminate(nil) }
        }
        return true
    }

    private static func run(_ path: String, _ arguments: [String]) {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: path)
        process.arguments = arguments
        try? process.run()
        process.waitUntilExit()
    }

    /// Where a translocated app really lives, through Security.framework's translocation SPI
    /// (looked up at runtime, as it has no public header).
    private static func originalURL(_ url: URL) -> URL? {
        guard let security = dlopen("/System/Library/Frameworks/Security.framework/Security", RTLD_LAZY),
              let isSymbol = dlsym(security, "SecTranslocateIsTranslocatedURL"),
              let originalSymbol = dlsym(security, "SecTranslocateCreateOriginalPathForURL") else { return nil }
        typealias IsTranslocated = @convention(c) (CFURL, UnsafeMutablePointer<DarwinBoolean>, UnsafeMutablePointer<Unmanaged<CFError>?>?) -> DarwinBoolean
        typealias Original = @convention(c) (CFURL, UnsafeMutablePointer<Unmanaged<CFError>?>?) -> Unmanaged<CFURL>?
        var translocated: DarwinBoolean = false
        guard unsafeBitCast(isSymbol, to: IsTranslocated.self)(url as CFURL, &translocated, nil).boolValue, translocated.boolValue,
              let original = unsafeBitCast(originalSymbol, to: Original.self)(url as CFURL, nil)?.takeRetainedValue() else { return nil }
        return (original as URL).standardizedFileURL
    }
}
