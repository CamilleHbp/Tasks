import AppIntents
import Foundation
import Darwin
import SwiftUI
import WidgetKit
import OSLog
import CryptoKit

struct TaskSnapshot: Codable {
    var version = 1
    var revision: Int64 = 0
    var locale = "en"
    var tasks: [TaskRow] = []
    var tags: [TagRow]? = []
    var isAvailable = true
    enum CodingKeys: String, CodingKey { case version, revision, locale, tasks, tags }
    struct TagRow: Codable { let id: String; let name: String; var color: String? = nil; var icon: String? = nil }
    struct TaskRow: Codable, Identifiable {
        let id: String
        let title: String
        let completed: Bool
        let dueDate: String?
        let dueTime: String?
        let tags: [String]?
    }
    static var directory: URL? {
        // Foundation's home is the sandbox container. Resolve the account home for
        // the single external directory granted by Widget.entitlements.
        guard let home = getpwuid(getuid())?.pointee.pw_dir else { return nil }
        return URL(fileURLWithPath: String(cString: home), isDirectory: true)
            .appendingPathComponent("Library/Application Support/VerveTwoDoWidget", isDirectory: true)
    }
    static func syncOverdue(in directory: URL?, now: Date) -> Bool {
        guard let path = directory?.appendingPathComponent("last-confirmed"),
              let text = try? String(contentsOf: path, encoding: .utf8),
              let milliseconds = Int64(text) else { return true }
        let age = now.timeIntervalSince1970 - Double(milliseconds) / 1000
        // Allow three background checks before showing the recovery link.
        return age < 0 || age >= 15 * 60
    }

    static func read(from directory: URL? = TaskSnapshot.directory) -> TaskSnapshot {
        let logger = Logger(subsystem: "studio.camille.vervetwodo.widgets.extension", category: "snapshot")
        do {
            guard let path = directory?.appendingPathComponent("snapshot.json") else {
                throw CocoaError(.fileNoSuchFile)
            }
            let data = try Data(contentsOf: path)
            guard data.count <= 4 * 1024 * 1024 else { throw CocoaError(.fileReadTooLarge) }
            let snapshot = try JSONDecoder().decode(TaskSnapshot.self, from: data)
            guard snapshot.version == 1 else { throw CocoaError(.fileReadCorruptFile) }
            logger.info("Loaded widget snapshot revision \(snapshot.revision), tasks \(snapshot.tasks.count)")
            return snapshot
        } catch {
            logger.error("Widget snapshot unavailable: \(error.localizedDescription, privacy: .public)")
            return TaskSnapshot(locale: Locale.current.language.languageCode?.identifier == "fr" ? "fr" : "en", isAvailable: false)
        }
    }
}

// Runs inside the widget extension: the companion app has no App Intents metadata, so
// opening it would drop the action. The extension may write only to commands/.
struct CompleteTaskIntent: AppIntent {
    static var title: LocalizedStringResource = "Check or uncheck task"
    static var openAppWhenRun = false
    @Parameter(title: "Task") var taskID: String
    @Parameter(title: "Completed", default: true) var completed: Bool
    init() {}
    init(taskID: String, completed: Bool = true) { self.taskID = taskID; self.completed = completed }

    func perform() async throws -> some IntentResult {
        guard let directory = TaskSnapshot.directory else {
            throw NSError(domain: "VerveTwoDo", code: 1, userInfo: [NSLocalizedDescriptionKey: String(localized: "Open Tasks Widgets to connect this widget.")])
        }
        struct Command: Encodable {
            let version = 1
            let id: String
            let action = "complete"
            let taskId: String
            let title = ""
            let completed: Bool
        }
        let inbox = directory.appendingPathComponent("commands")
        try FileManager.default.createDirectory(at: inbox, withIntermediateDirectories: true)
        let id = UUID().uuidString
        let command = Command(id: id, taskId: taskID, completed: completed)
        // Name order is apply order for the helper, so a quick check then uncheck stays in sequence.
        let name = String(format: "%013.0f-", Date().timeIntervalSince1970 * 1000) + id
        try JSONEncoder().encode(command).write(to: inbox.appendingPathComponent("\(name).json"), options: .atomic)
        WidgetToggleStore.record(taskID, completed: completed)
        WidgetCenter.shared.reloadTimelines(ofKind: "studio.camille.vervetwodo.preview.tasks")
        return .result()
    }
}

/// Taps show at once, before the helper applies them. A task checked in the widget stays
/// in place, checked, for a short while so a mistaken tap can be undone where it happened.
enum WidgetToggleStore {
    static let window: TimeInterval = 10 * 60
    static let stallAfter: TimeInterval = 20
    private static let key = "widget-toggles"

    static func record(_ id: String, completed: Bool, defaults: UserDefaults = .standard) {
        var all = defaults.dictionary(forKey: key) as? [String: [String: Double]] ?? [:]
        all[id] = ["completed": completed ? 1 : 0, "at": Date().timeIntervalSince1970]
        defaults.set(all, forKey: key)
    }

    /// Current overrides and the time the oldest one expires.
    static func overrides(now: Date = Date(), defaults: UserDefaults = .standard) -> ([String: Bool], Date?) {
        let all = defaults.dictionary(forKey: key) as? [String: [String: Double]] ?? [:]
        let live = all.filter { now.timeIntervalSince1970 - ($0.value["at"] ?? 0) < window }
        if live.count != all.count { defaults.set(live, forKey: key) }
        let expiry = live.values.compactMap { $0["at"] }.min().map { Date(timeIntervalSince1970: $0 + window) }
        return (live.mapValues { $0["completed"] == 1 }, expiry)
    }

    /// Age of the oldest command the helper has not applied yet.
    static func pendingAge(now: Date = Date()) -> TimeInterval? {
        guard let inbox = TaskSnapshot.directory?.appendingPathComponent("commands"),
              let files = try? FileManager.default.contentsOfDirectory(at: inbox, includingPropertiesForKeys: [.contentModificationDateKey])
        else { return nil }
        let dates = files.filter { $0.pathExtension == "json" }
            .compactMap { try? $0.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate }
        return dates.min().map { now.timeIntervalSince($0) }
    }
}

// Navigation is scoped to the widget's filters, sort, grouping and size. An
// absolute destination makes retries of the same button action idempotent.
enum WidgetPageStore {
    static func key(_ values: [String]) -> String {
        let data = try! JSONEncoder().encode(values)
        return "widget-page-" + SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }
    static func read(_ key: String, defaults: UserDefaults = .standard) -> Int {
        max(0, defaults.integer(forKey: key))
    }
    static func write(_ page: Int, key: String, defaults: UserDefaults = .standard) {
        guard key.hasPrefix("widget-page-"), key.count == 76 else { return }
        defaults.set(max(0, page), forKey: key)
    }
    static func clamped(_ page: Int, count: Int) -> Int { min(max(0, page), max(0, count - 1)) }
    static func collapsedGroups(_ key: String, defaults: UserDefaults = .standard) -> Set<String> {
        Set(defaults.stringArray(forKey: key + "-collapsed") ?? [])
    }
    static func setCollapsed(_ collapsed: Bool, group: String, key: String, defaults: UserDefaults = .standard) {
        guard key.hasPrefix("widget-page-"), key.count == 76 else { return }
        var groups = collapsedGroups(key, defaults: defaults)
        if collapsed { groups.insert(group) } else { groups.remove(group) }
        defaults.set(groups.sorted(), forKey: key + "-collapsed")
        write(0, key: key, defaults: defaults)
    }

}

struct ChangeWidgetPageIntent: AppIntent {
    static var title: LocalizedStringResource = "Change task page"
    static var openAppWhenRun = false
    @Parameter(title: "Widget") var navigationKey: String
    @Parameter(title: "Page") var page: Int
    init() {}
    init(key: String, page: Int) { navigationKey = key; self.page = page }
    func perform() async throws -> some IntentResult {
        WidgetPageStore.write(page, key: navigationKey)
        WidgetCenter.shared.reloadTimelines(ofKind: "studio.camille.vervetwodo.preview.tasks")
        return .result()
    }
}

struct SetWidgetGroupCollapsedIntent: AppIntent {
    static var title: LocalizedStringResource = "Expand or collapse group"
    static var openAppWhenRun = false
    @Parameter(title: "Widget") var navigationKey: String
    @Parameter(title: "Group") var group: String
    @Parameter(title: "Collapsed") var collapsed: Bool
    init() {}
    init(key: String, group: String, collapsed: Bool) {
        navigationKey = key; self.group = group; self.collapsed = collapsed
    }
    func perform() async throws -> some IntentResult {
        WidgetPageStore.setCollapsed(collapsed, group: group, key: navigationKey)
        WidgetCenter.shared.reloadTimelines(ofKind: "studio.camille.vervetwodo.preview.tasks")
        return .result()
    }
}

struct TaskEntry: TimelineEntry {
    var date: Date
    var snapshot: TaskSnapshot
    var collection: WidgetCollection = .all
    var tagIDs: [String] = []
    var tagMatch: WidgetTagMatch = .any
    var sortOrder: WidgetSortOrder = .dueDate
    var showTagIcons = true
    var dateSeparators: WidgetDateSeparators = .relative
    var navigationKey = ""
    var pageIndex = 0
    var collapsedGroups: Set<String> = []
    /// Widget taps not yet reflected in, or kept visible beyond, the snapshot.
    var overrides: [String: Bool] = [:]
    var syncStalled = false
}

struct TaskWidgetView: View {
    @Environment(\.widgetFamily) private var family
    let entry: TaskEntry
    var body: some View {
        GeometryReader { geometry in
            content(height: geometry.size.height)
        }
        .containerBackground(.background, for: .widget)
        .environment(\.locale, Locale(identifier: entry.snapshot.locale))
    }

    func content(height: CGFloat) -> some View {
        let tasks = entry.filteredTasks(formatter: DateFormatter())
        let compact = height < 180
        let rowHeight: CGFloat = compact ? 48 : 54
        let headingHeight: CGFloat = compact ? 20 : 26
        let pages = entry.taskPages(tasks: tasks, height: max(rowHeight + headingHeight, height - 56),
                                    singleTask: family == .systemSmall, rowHeight: rowHeight, headingHeight: headingHeight)
        let page = WidgetPageStore.clamped(entry.pageIndex, count: pages.count)
        let rows = pages.isEmpty ? [] : pages[page]
        return VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(entry.collection.title).font(.headline).lineLimit(1)
                let open = tasks.filter { !$0.completed }.count
                if open > 0 {
                    Text(open, format: .number).font(.headline.monospacedDigit()).foregroundStyle(.secondary)
                        .accessibilityLabel(Text("\(open) open tasks"))
                }
                Spacer()
                if entry.snapshot.isAvailable {
                    Link(destination: URL(string: "vervetwodo-widgets://sync")!) {
                        Image(systemName: "arrow.clockwise").frame(width: 24, height: 24)
                            .accessibilityLabel(Text("Sync now"))
                    }
                }
                if family != .systemSmall {
                    Link(destination: URL(string: "vervetwodo-widgets://add")!) {
                        Image(systemName: "plus").frame(width: 24, height: 24)
                            .accessibilityLabel(Text("Add task"))
                    }
                }
            }
            if !entry.snapshot.isAvailable {
                // The whole disconnected widget leads to the connection settings (see widgetURL).
                Text("Open Tasks Widgets to connect this widget.").font(.caption).foregroundStyle(.secondary)
                Link(destination: URL(string: "vervetwodo-widgets://settings")!) {
                    Text("Connect").font(.caption.weight(.semibold))
                        .padding(.horizontal, 12).padding(.vertical, 6)
                        .background(Color.accentColor.opacity(0.15), in: Capsule())
                }.padding(.top, 4)
            } else if tasks.isEmpty {
                Text("No tasks in this collection.").font(.caption).foregroundStyle(.secondary)
            } else {
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(rows) { row in
                        if let task = row.task {
                            taskRow(task).frame(height: rowHeight)
                        } else {
                            let collapsed = entry.collapsedGroups.contains(row.id)
                            Button(intent: SetWidgetGroupCollapsedIntent(key: entry.navigationKey, group: row.id, collapsed: !collapsed)) {
                            HStack(spacing: 6) {
                                Image(systemName: collapsed ? "chevron.right" : "chevron.down")
                                    .font(.system(size: 9, weight: .semibold)).accessibilityHidden(true)
                                Text(row.title).font(.caption.weight(.semibold)).lineLimit(1).layoutPriority(1)
                                Rectangle().fill(.quaternary).frame(height: 1)
                                Text(row.count, format: .number).font(.caption2).foregroundStyle(.secondary)
                            }
                            .foregroundStyle(row.id == "Overdue" ? Color.red : Color.secondary)
                            .frame(height: headingHeight)
                            .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel(Text(row.title) + Text(": \(row.count)"))
                            .accessibilityValue(collapsed ? Text("Collapsed") : Text("Expanded"))
                            .accessibilityHint(collapsed ? Text("Expand group") : Text("Collapse group"))
                            .accessibilityAddTraits(.isHeader)
                        }
                    }
                }
            }
            Spacer(minLength: 0)
            if entry.syncStalled {
                Link(destination: URL(string: "vervetwodo-widgets://sync")!) {
                    Label("Waiting to sync. Open Tasks Widgets.", systemImage: "arrow.triangle.2.circlepath")
                        .font(.caption2).foregroundStyle(.secondary).lineLimit(1)
                }
            }
            if !pages.isEmpty {
                HStack(spacing: 4) {
                    Button(intent: ChangeWidgetPageIntent(key: entry.navigationKey, page: page - 1)) {
                        Image(systemName: "chevron.left").frame(width: 28, height: 24).contentShape(Rectangle())
                    }
                    .disabled(page == 0)
                    .accessibilityLabel(Text("Previous page"))
                    Spacer(minLength: 0)
                    Text("\(page + 1) / \(pages.count)").font(.caption.monospacedDigit()).lineLimit(1)
                        .accessibilityLabel(Text("Page \(page + 1) of \(pages.count)"))
                    Spacer(minLength: 0)
                    Button(intent: ChangeWidgetPageIntent(key: entry.navigationKey, page: page + 1)) {
                        Image(systemName: "chevron.right").frame(width: 28, height: 24).contentShape(Rectangle())
                    }
                    .disabled(page + 1 >= pages.count)
                    .accessibilityLabel(Text("Next page"))
                }.buttonStyle(.plain)
            }
        }
        .widgetURL(!entry.snapshot.isAvailable ? URL(string: "vervetwodo-widgets://settings") : family == .systemSmall ? rows.compactMap(\.task).first.map { taskURL($0.id) } ?? URL(string: "vervetwodo-widgets://open") : URL(string: "vervetwodo-widgets://open"))
    }

    func taskRow(_ task: TaskSnapshot.TaskRow) -> some View {
        let assigned = (task.tags ?? []).compactMap { id in entry.snapshot.tags?.first { $0.id == id } }
        return HStack(alignment: .center, spacing: 6) {
            // The whole leading column is the checkbox, not just the 20 pt ring.
            Button(intent: CompleteTaskIntent(taskID: task.id, completed: !task.completed)) {
                Group {
                    if task.completed {
                        Image(systemName: "checkmark.circle.fill").font(.system(size: 20)).foregroundStyle(.secondary)
                    } else {
                        CategoryRingView(tags: assigned)
                    }
                }.frame(width: 30).frame(maxHeight: .infinity).contentShape(Rectangle())
            }.buttonStyle(.plain)
                .accessibilityLabel((task.completed ? Text("Mark as not done") : Text("Mark as done")) + Text(": " + task.title))
                .accessibilityValue(task.completed ? Text("Done") : Text("Not done"))
            Link(destination: taskURL(task.id)) {
                HStack(spacing: 6) {
                    VStack(alignment: .leading, spacing: 3) {
                        Text(task.title).font(.system(size: 13, weight: .medium)).lineLimit(2)
                            .strikethrough(task.completed)
                            .foregroundStyle(task.completed ? .secondary : .primary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                        if let due = task.dueDate, !due.isEmpty, entry.collection != .today {
                            Text(formattedDate(due) + (task.dueTime.map { " · " + $0 } ?? ""))
                                .font(.caption2).foregroundStyle(.secondary).lineLimit(1)
                        } else if let time = task.dueTime, !time.isEmpty {
                            Text(time).font(.caption2).foregroundStyle(.secondary)
                        }
                    }
                    if entry.showTagIcons && !assigned.isEmpty {
                        HStack(spacing: 4) {
                            ForEach(Array(assigned.prefix(2)), id: \.id) { tag in CategoryIconView(tag: tag) }
                            if assigned.count > 2 { Text("+\(assigned.count - 2)").font(.caption2) }
                        }.accessibilityLabel(assigned.map { $0.name }.joined(separator: ", "))
                    }
                    if family != .systemSmall {
                        Image(systemName: "chevron.right").font(.system(size: 9, weight: .semibold)).foregroundStyle(.tertiary)
                    }
                }.frame(maxWidth: .infinity, maxHeight: .infinity).contentShape(Rectangle())
            }.buttonStyle(.plain).accessibilityHint(Text("Open task to view or edit"))
        }
    }

    func taskURL(_ id: String) -> URL {
        var url = URLComponents()
        url.scheme = "vervetwodo-widgets"; url.host = "task"
        url.queryItems = [URLQueryItem(name: "id", value: id)]
        return url.url!
    }
}

enum WidgetCollection: String, AppEnum {
    case all, today, upcoming, overdue, undated
    var title: LocalizedStringKey {
        switch self { case .all: return "Tasks"; case .today: return "Today"; case .upcoming: return "Upcoming"; case .overdue: return "Overdue"; case .undated: return "No due date" }
    }
    static var typeDisplayRepresentation: TypeDisplayRepresentation = "Collection"
    static var caseDisplayRepresentations: [WidgetCollection: DisplayRepresentation] = [.all: "All tasks", .today: "Today", .upcoming: "Upcoming", .overdue: "Overdue", .undated: "No due date"]
}

struct TagEntity: AppEntity {
    var id: String
    var name: String
    static var typeDisplayRepresentation: TypeDisplayRepresentation = "Tag"
    static var defaultQuery = TagQuery()
    var displayRepresentation: DisplayRepresentation { DisplayRepresentation(title: "\(name)") }
}
struct TagQuery: EntityStringQuery {
    func entities(for identifiers: [String]) async throws -> [TagEntity] {
        all().filter { identifiers.contains($0.id) }
    }
    func entities(matching string: String) async throws -> [TagEntity] {
        all().filter { $0.name.localizedCaseInsensitiveContains(string) }
    }
    func suggestedEntities() async throws -> [TagEntity] { all() }
    private func all() -> [TagEntity] {
        (TaskSnapshot.read().tags ?? []).map { TagEntity(id: $0.id, name: $0.name) }.sorted { $0.name.localizedCompare($1.name) == .orderedAscending }
    }
}
enum WidgetTagMatch: String, AppEnum {
    case any, all, untagged
    static var typeDisplayRepresentation: TypeDisplayRepresentation = "Tag matching"
    static var caseDisplayRepresentations: [WidgetTagMatch: DisplayRepresentation] = [
        .any: "Any selected tag", .all: "All selected tags", .untagged: "Untagged tasks"]
}

enum WidgetSortOrder: String, AppEnum {
    case dueDate, title
    static var typeDisplayRepresentation: TypeDisplayRepresentation = "Sort order"
    static var caseDisplayRepresentations: [WidgetSortOrder: DisplayRepresentation] = [
        .dueDate: "Due date", .title: "Title"]
}

enum WidgetDateSeparators: String, AppEnum {
    case relative, date, none
    static var typeDisplayRepresentation: TypeDisplayRepresentation = "Date separators"
    static var caseDisplayRepresentations: [WidgetDateSeparators: DisplayRepresentation] = [
        .relative: "Relative dates", .date: "Calendar dates", .none: "None"]
}

struct WidgetDisplayRow: Identifiable {
    var id: String
    var title = ""
    var count = 0
    var task: TaskSnapshot.TaskRow? = nil
}

struct CollectionIntent: WidgetConfigurationIntent {
    static var title: LocalizedStringResource = "Task collection"
    @Parameter(title: "Collection", default: .all) var collection: WidgetCollection
    @Parameter(title: "Additional tags") var tags: [TagEntity]?
    @Parameter(title: "Tag matching", default: .any) var tagMatch: WidgetTagMatch
    @Parameter(title: "Sort order", default: .dueDate) var sortOrder: WidgetSortOrder
    @Parameter(title: "Show tag icons", default: true) var showTagIcons: Bool
    @Parameter(title: "Date separators", default: .relative) var dateSeparators: WidgetDateSeparators
    // Retain the old parameter identity so existing single-tag widgets keep their filter.
    @Parameter(title: "Tag") var tag: TagEntity?
    static var parameterSummary: some ParameterSummary {
        Summary {
            \.$collection
            \.$tag
            \.$tags
            \.$tagMatch
            \.$sortOrder
            \.$showTagIcons
            \.$dateSeparators
        }
    }
}

struct CollectionProvider: AppIntentTimelineProvider {
    func placeholder(in context: Context) -> TaskEntry { TaskEntry(date: Date(), snapshot: TaskSnapshot()) }
    func snapshot(for configuration: CollectionIntent, in context: Context) async -> TaskEntry {
        var entry = TaskEntry(date: Date(), snapshot: TaskSnapshot.read(), collection: configuration.collection,
                  tagIDs: (configuration.tags?.map(\.id) ?? []) + (configuration.tag.map { [$0.id] } ?? []),
                  tagMatch: configuration.tagMatch, sortOrder: configuration.sortOrder,
                  showTagIcons: configuration.showTagIcons, dateSeparators: configuration.dateSeparators)
        entry.navigationKey = WidgetPageStore.key([
            configuration.collection.rawValue, configuration.tagMatch.rawValue,
            configuration.sortOrder.rawValue, configuration.dateSeparators.rawValue,
            String(configuration.showTagIcons), String(describing: context.family)
        ] + Array(Set(entry.tagIDs)).sorted())
        entry.pageIndex = WidgetPageStore.read(entry.navigationKey)
        entry.collapsedGroups = WidgetPageStore.collapsedGroups(entry.navigationKey)
        entry.overrides = WidgetToggleStore.overrides().0
        entry.syncStalled = entry.snapshot.isAvailable && (TaskSnapshot.syncOverdue(in: TaskSnapshot.directory, now: entry.date)
            || (WidgetToggleStore.pendingAge() ?? 0) > WidgetToggleStore.stallAfter)
        return entry
    }
    func timeline(for configuration: CollectionIntent, in context: Context) async -> Timeline<TaskEntry> {
        let entry = await snapshot(for: configuration, in: context)
        // Reload when a checked row should leave, and soon after a tap in case the helper is not running.
        var next = Date().addingTimeInterval(300)
        if let expiry = WidgetToggleStore.overrides().1 { next = min(next, expiry.addingTimeInterval(1)) }
        if let age = WidgetToggleStore.pendingAge(), age <= WidgetToggleStore.stallAfter {
            next = min(next, Date().addingTimeInterval(WidgetToggleStore.stallAfter - age + 1))
        }
        return Timeline(entries: [entry], policy: .after(next))
    }
}

extension TaskWidgetView {
    func formattedDate(_ value: String) -> String {
        let input = DateFormatter(); input.locale = Locale(identifier: "en_US_POSIX")
        input.calendar = Calendar(identifier: .gregorian); input.dateFormat = "yyyy-MM-dd"
        guard let date = input.date(from: value) else { return value }
        let output = DateFormatter(); output.locale = Locale(identifier: entry.snapshot.locale); output.dateStyle = .short
        return output.string(from: date)
    }
}

extension TaskEntry {
    func sectionKey(_ task: TaskSnapshot.TaskRow) -> String {
        guard let due = task.dueDate, !due.isEmpty else { return "No due date" }
        if dateSeparators == .date { return due }
        let calendar = Calendar(identifier: .gregorian)
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.calendar = calendar; formatter.dateFormat = "yyyy-MM-dd"
        guard let value = formatter.date(from: due) else { return "No due date" }
        let today = calendar.startOfDay(for: date)
        let tomorrow = calendar.date(byAdding: .day, value: 1, to: today)!
        let weekday = calendar.component(.weekday, from: today)
        let nextMonday = calendar.date(byAdding: .day, value: (9 - weekday) % 7 == 0 ? 7 : (9 - weekday) % 7, to: today)!
        let followingMonday = calendar.date(byAdding: .day, value: 7, to: nextMonday)!
        if value < today { return "Overdue" }
        if value == today { return "Today" }
        if value == tomorrow { return "Tomorrow" }
        if value < nextMonday { return "This week" }
        if value < followingMonday { return "Next week" }
        return "Later"
    }

    func visibleRows(tasks: [TaskSnapshot.TaskRow], height: CGFloat, singleTask: Bool = false) -> [WidgetDisplayRow] {
        taskPages(tasks: tasks, height: height, singleTask: singleTask).first ?? []
    }

    func taskPages(tasks: [TaskSnapshot.TaskRow], height: CGFloat, singleTask: Bool = false,
                   rowHeight: CGFloat = 54, headingHeight: CGFloat = 26) -> [[WidgetDisplayRow]] {
        guard height >= rowHeight + (dateSeparators == .none ? 0 : headingHeight) else { return [] }
        let keys = ["Overdue", "Today", "Tomorrow", "This week", "Next week", "Later", "No due date"]
        let grouped = Dictionary(grouping: tasks, by: sectionKey)
        let order = dateSeparators == .date ? grouped.keys.sorted { ($0 == "No due date" ? "9999" : $0) < ($1 == "No due date" ? "9999" : $1) } : keys
        let groups = dateSeparators == .none ? [("", tasks)] : order.compactMap { key in grouped[key].map { (key, $0) } }
        var pages: [[WidgetDisplayRow]] = []
        var result: [WidgetDisplayRow] = []
        var remaining = height
        var previousKey: String? = nil
        var count = 0
        for (key, items) in groups {
            let formatter = DateFormatter(); formatter.locale = Locale(identifier: "en_US_POSIX")
            formatter.dateFormat = "yyyy-MM-dd"
            let parsed = formatter.date(from: key)
            formatter.locale = Locale(identifier: snapshot.locale); formatter.dateStyle = .medium; formatter.timeStyle = .none
            let bundle = Bundle.main.path(forResource: snapshot.locale == "fr" ? "fr" : "en", ofType: "lproj").flatMap(Bundle.init(path:)) ?? .main
            let title = parsed.map { formatter.string(from: $0) } ?? bundle.localizedString(forKey: key, value: key, table: nil)
            if !key.isEmpty && collapsedGroups.contains(key) {
                if remaining < headingHeight {
                    if !result.isEmpty { pages.append(result) }
                    result = []; remaining = height; count = 0
                }
                result.append(WidgetDisplayRow(id: key, title: title, count: items.count))
                remaining -= headingHeight; previousKey = key
                continue
            }
            for task in items {
                let needsHeading = !key.isEmpty && previousKey != key
                if remaining < rowHeight + (needsHeading ? headingHeight : 0) || singleTask && count == 1 {
                    if !result.isEmpty { pages.append(result) }
                    result = []; remaining = height; previousKey = nil; count = 0
                }
                if !key.isEmpty && previousKey != key {
                    result.append(WidgetDisplayRow(id: key, title: title, count: items.count))
                    remaining -= headingHeight
                }
                result.append(WidgetDisplayRow(id: task.id, task: task))
                remaining -= rowHeight; previousKey = key; count += 1
            }
        }
        if !result.isEmpty { pages.append(result) }
        return pages
    }


    func filteredTasks(formatter: DateFormatter) -> [TaskSnapshot.TaskRow] {
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.dateFormat = "yyyy-MM-dd"
        let today = formatter.string(from: date)
        let selected = Set(tagIDs)
        return snapshot.tasks.map { task in
            guard let completed = overrides[task.id], completed != task.completed else { return task }
            return TaskSnapshot.TaskRow(id: task.id, title: task.title, completed: completed,
                                        dueDate: task.dueDate, dueTime: task.dueTime, tags: task.tags)
        }.filter { task in
            // Tasks checked in this widget stay visible, checked, until their override expires.
            guard !task.completed || overrides[task.id] == true else { return false }
            let assigned = Set(task.tags ?? [])
            switch tagMatch {
            case .untagged: guard assigned.isEmpty else { return false }
            case .any: guard selected.isEmpty || !selected.isDisjoint(with: assigned) else { return false }
            case .all: guard selected.isSubset(of: assigned) else { return false }
            }
            let due = task.dueDate ?? ""
            switch collection {
            case .all: return true
            case .today: return due == today
            case .upcoming: return due >= today
            case .overdue: return !due.isEmpty && due < today
            case .undated: return due.isEmpty
            }
        }.sorted {
            if sortOrder == .title {
                let order = $0.title.compare($1.title, options: [.caseInsensitive, .diacriticInsensitive],
                                             locale: Locale(identifier: snapshot.locale))
                return order == .orderedSame ? $0.id < $1.id : order == .orderedAscending
            }
            let a = ($0.dueDate?.isEmpty == false ? $0.dueDate! : "9999-99-99") + ($0.dueTime?.isEmpty == false ? $0.dueTime! : "99:99")
            let b = ($1.dueDate?.isEmpty == false ? $1.dueDate! : "9999-99-99") + ($1.dueTime?.isEmpty == false ? $1.dueTime! : "99:99")
            return a == b ? $0.id < $1.id : a < b
        }
    }
}

struct VerveTaskWidget: Widget {
    let kind = "studio.camille.vervetwodo.preview.tasks"
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: kind, intent: CollectionIntent.self, provider: CollectionProvider()) { TaskWidgetView(entry: $0) }
            .configurationDisplayName("Tasks")
            .description("Keep your tasks nearby.")
            .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}

@main
struct VerveWidgetBundle: WidgetBundle {
    var body: some Widget { VerveTaskWidget() }
}

private func categoryColor(_ hex: String?) -> Color {
    let raw = UInt64((hex ?? "#3877B8").replacingOccurrences(of: "#", with: ""), radix: 16) ?? 0x3877B8
    return Color(red: Double((raw >> 16) & 255) / 255, green: Double((raw >> 8) & 255) / 255, blue: Double(raw & 255) / 255)
}
struct CategoryRingView: View {
    let tags: [TaskSnapshot.TagRow]
    var body: some View {
        ZStack {
            Circle().stroke(.primary, lineWidth: 4)
            if tags.isEmpty { Circle().stroke(.primary, lineWidth: 2) }
            else { ForEach(Array(tags.enumerated()), id: \.offset) { index, tag in
                Circle().trim(from: Double(index) / Double(tags.count), to: Double(index + 1) / Double(tags.count))
                    .stroke(categoryColor(tag.color), lineWidth: 2).rotationEffect(.degrees(-90))
            } }
        }.frame(width: 20, height: 20)
    }
}
struct CategoryIconView: View {
    let tag: TaskSnapshot.TagRow
    var body: some View {
        let requested = tag.icon ?? "tag"
        let id = requested.range(of: "^[a-z][a-z0-9_]*$", options: .regularExpression) != nil ? requested : "tag"
        let artwork = Bundle.main.url(forResource: "category-" + id, withExtension: "png")
            ?? Bundle.main.url(forResource: "category-tag", withExtension: "png")
        if let url = artwork, let image = NSImage(contentsOf: url) {
            Image(nsImage: image).resizable().scaledToFit().padding(2).frame(width: 24, height: 24)
                .background(Color(red: 0.96, green: 0.90, blue: 0.78), in: Circle()).accessibilityHidden(true)
        } else { Image(systemName: "tag").frame(width: 24, height: 24).accessibilityHidden(true) }
    }
}
