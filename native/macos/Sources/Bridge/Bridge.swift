import Foundation
import AppKit
import Darwin

@main
@MainActor
struct NativeBridge {
    static var resourceBundle = Bundle.main

    static func localized(_ key: String) -> String {
        resourceBundle.localizedString(forKey: key, value: key, table: nil)
    }

    static func storageDirectory(language: String) throws -> URL {
        let locale = language == "system" ? (Locale.current.language.languageCode?.identifier ?? "en") : language
        if let path = Bundle.main.path(forResource: locale == "fr" ? "fr" : "en", ofType: "lproj"),
           let bundle = Bundle(path: path) { resourceBundle = bundle }
        let home = FileManager.default.homeDirectoryForCurrentUser
        let destination = home.appendingPathComponent("Library/Application Support/VerveTwoDo/Preview", isDirectory: true)
        if FileManager.default.fileExists(atPath: destination.path) { return destination }
        let legacy = home.appendingPathComponent("Library/Group Containers/group.studio.camille.vervetwodo.preview/Preview", isDirectory: true)
        // Inspect metadata only. Never enumerate or open the protected legacy container
        // until the user explicitly selects it in the system folder picker.
        var metadata = stat()
        let exists = lstat(legacy.path, &metadata) == 0
        if !exists && errno == ENOENT { return destination }
        let panel = NSOpenPanel()
        panel.title = localized("Transfer existing VerveTwoDo data")
        panel.message = localized("Choose the Preview folder to copy your existing tasks and synchronization settings into VerveTwoDo’s own storage. The original data will be kept. Quit any older VerveTwoDo version first.")
        panel.prompt = localized("Transfer")
        panel.canChooseDirectories = true
        panel.canChooseFiles = false
        panel.allowsMultipleSelection = false
        panel.directoryURL = legacy
        NSApplication.shared.setActivationPolicy(.accessory)
        NSApplication.shared.activate(ignoringOtherApps: true)
        guard panel.runModal() == .OK, let selected = panel.url else { exit(2) }
        guard selected.standardizedFileURL == legacy.standardizedFileURL else {
            throw NSError(domain: "VerveTwoDo", code: 4, userInfo: [NSLocalizedDescriptionKey: localized("Choose the existing VerveTwoDo Preview folder.")])
        }
        let scoped = selected.startAccessingSecurityScopedResource()
        defer { if scoped { selected.stopAccessingSecurityScopedResource() } }
        try copyStorage(from: selected, to: destination)
        return destination
    }

    static func copyStorage(from source: URL, to destination: URL) throws {
        let fm = FileManager.default
        // Match the JVM's POSIX lock so a resident older app cannot change a replica
        // while it is being copied. Keep the original directory intact for recovery.
        let descriptor = open(source.appendingPathComponent("desktop.lock").path, O_CREAT | O_RDWR, 0o600)
        guard descriptor >= 0 else { throw CocoaError(.fileReadNoPermission) }
        defer { close(descriptor) }
        var lock = flock()
        lock.l_type = Int16(F_WRLCK)
        lock.l_whence = Int16(SEEK_SET)
        guard fcntl(descriptor, F_SETLK, &lock) == 0 else {
            throw NSError(domain: "VerveTwoDo", code: 5, userInfo: [NSLocalizedDescriptionKey: localized("Quit the older VerveTwoDo app, then try again.")])
        }
        let parent = destination.deletingLastPathComponent()
        try fm.createDirectory(at: parent, withIntermediateDirectories: true)
        let staging = parent.appendingPathComponent(".migration-" + UUID().uuidString, isDirectory: true)
        defer { try? fm.removeItem(at: staging) }
        try fm.copyItem(at: source, to: staging)
        try fm.moveItem(at: staging, to: destination)
    }

    static func main() {
        do {
            switch CommandLine.arguments.dropFirst().first {
            case "identity":
                print(Bundle.main.bundleIdentifier ?? "unbundled")
            case "storage":
                let language = String(data: FileHandle.standardInput.readDataToEndOfFile(), encoding: .utf8) ?? "system"
                print(try storageDirectory(language: language).path)
            default:
                throw NSError(domain: "VerveTwoDo", code: 3, userInfo: [NSLocalizedDescriptionKey: "Unknown bridge command"])
            }
        } catch {
            if CommandLine.arguments.dropFirst().first == "storage" {
                let alert = NSAlert()
                alert.messageText = localized("Storage could not be opened")
                alert.informativeText = error.localizedDescription
                alert.runModal()
            }
            FileHandle.standardError.write(Data("\(error.localizedDescription)\n".utf8))
            exit(1)
        }
    }
}
