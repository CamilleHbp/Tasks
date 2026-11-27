import Foundation
import WidgetKit

struct HelperStatus: Decodable, Equatable {
    var authenticated = false
    var workspace = ""
    var origin = ""
    var confirmed = false
    var lastSuccessAt: Int64 = 0
    var busy = false
    var error: String?
    var tasks = 0
}

private struct HelperResponse: Decodable {
    var id: String?
    var event: String?
    var status: HelperStatus?
    var error: String?
}

struct HelperFailure: LocalizedError {
    let code: String
    var errorDescription: String? { L("error." + code, fallback: L("error.generic")) }
}

/// Owns the bundled Go helper, which syncs the workspace and keeps the widget files current.
/// Access codes cross only the helper's stdin, never arguments or files.
@MainActor
final class Helper: ObservableObject {
    @Published private(set) var status = HelperStatus()
    @Published private(set) var running = false
    @Published private(set) var receivedStatus = false

    static let support = FileManager.default.homeDirectoryForCurrentUser
        .appendingPathComponent("Library/Application Support", isDirectory: true)
    /// Read-only for the sandboxed widget via Widget.entitlements; keep in sync with TaskSnapshot.directory.
    static let widgetDirectory = support.appendingPathComponent("VerveTwoDoWidget", isDirectory: true)
    static let dataDirectory = support.appendingPathComponent("VerveTwoDo Widgets", isDirectory: true)

    private var process: Process?
    private var input: FileHandle?
    private var buffer = Data()
    private var pending: [String: CheckedContinuation<HelperResponse, Never>] = [:]
    private var stopping = false
    private var publishedConfirmation: Int64?

    func start() {
        guard !stopping, process == nil, let executable = Bundle.main.url(forResource: "vervetwodo-widget-helper", withExtension: nil) else { return }
        let process = Process()
        process.executableURL = executable
        process.arguments = ["--data-dir", Self.dataDirectory.path, "--widget-dir", Self.widgetDirectory.path, "--platform", "macos"]
        let stdin = Pipe(), stdout = Pipe()
        process.standardInput = stdin
        process.standardOutput = stdout
        process.standardError = FileHandle.nullDevice
        stdout.fileHandleForReading.readabilityHandler = { [weak self] handle in
            let data = handle.availableData
            if data.isEmpty { handle.readabilityHandler = nil; return }
            DispatchQueue.main.async { [weak self] in
                guard self?.process === process else { return }
                self?.receive(data)
            }
        }
        process.terminationHandler = { [weak self] _ in
            Task { @MainActor in self?.terminated() }
        }
        do { try process.run() } catch {
            stdout.fileHandleForReading.readabilityHandler = nil
            retryStart()
            return
        }
        self.process = process
        input = stdin.fileHandleForWriting
        running = true
        Task { _ = try? await request("locale", ["locale": Localization.resolved]) }
    }

    func stop() {
        stopping = true
        try? input?.close()
        // EOF lets the helper flush and exit independently of the AppKit run loop.
    }

    private func terminated() {
        process = nil
        input = nil
        running = false
        receivedStatus = false
        buffer.removeAll()
        for continuation in pending.values { continuation.resume(returning: HelperResponse(error: "helper_stopped")) }
        pending = [:]
        retryStart()
    }

    private func retryStart() {
        guard !stopping else { return }
        DispatchQueue.main.asyncAfter(deadline: .now() + 5) { [weak self] in self?.start() }
    }

    private func receive(_ data: Data) {
        buffer.append(data)
        while let newline = buffer.firstIndex(of: 0x0A) {
            let line = buffer[buffer.startIndex..<newline]
            buffer.removeSubrange(buffer.startIndex...newline)
            guard let response = try? JSONDecoder().decode(HelperResponse.self, from: line) else { continue }
            if let status = response.status {
                // Share only the confirmation timestamp with the extension. Task file
                // modification times do not advance when a healthy workspace is unchanged.
                if status.authenticated && status.confirmed && status.lastSuccessAt != publishedConfirmation {
                    let stamp = Self.widgetDirectory.appendingPathComponent("last-confirmed")
                    if (try? Data(String(status.lastSuccessAt).utf8).write(to: stamp, options: .atomic)) != nil {
                        publishedConfirmation = status.lastSuccessAt
                    }
                }
                self.status = status
                receivedStatus = true
            }
            if let id = response.id, let continuation = pending.removeValue(forKey: id) {
                continuation.resume(returning: response)
            }
            if response.event == "changed" { WidgetCenter.shared.reloadAllTimelines() }
        }
    }

    @discardableResult
    func request(_ method: String, _ fields: [String: String] = [:]) async throws -> HelperStatus {
        guard let input else { throw HelperFailure(code: "helper_stopped") }
        let id = UUID().uuidString
        var body = fields
        body["id"] = id
        body["method"] = method
        var line = try JSONSerialization.data(withJSONObject: body)
        line.append(0x0A)
        let response = await withCheckedContinuation { continuation in
            pending[id] = continuation
            do { try input.write(contentsOf: line) } catch {
                pending.removeValue(forKey: id)
                continuation.resume(returning: HelperResponse(error: "helper_stopped"))
            }
        }
        if let error = response.error { throw HelperFailure(code: error) }
        if method == "sync" || method == "disconnect" {
            WidgetCenter.shared.reloadAllTimelines()
        }
        return response.status ?? status
    }

    func setLocale() {
        Task {
            _ = try? await request("locale", ["locale": Localization.resolved])
            WidgetCenter.shared.reloadAllTimelines()
        }
    }
}
