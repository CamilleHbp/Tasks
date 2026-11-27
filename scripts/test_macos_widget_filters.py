"""Run the production macOS widget filters against mixed date/tag fixtures."""
from pathlib import Path
import re
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'native/macos/Sources/Widgets/VerveWidgets.swift'


class WidgetFiltersTest(unittest.TestCase):
    def test_filters(self):
        source = SOURCE.read_text()
        pieces = [source[source.index(start):source.index(end)] for start, end in [
            ('struct TaskSnapshot:', '\nstruct CompleteTaskIntent:'),
            ('enum WidgetPageStore {', '\nstruct ChangeWidgetPageIntent:'),
            ('struct TaskEntry:', '\nstruct TaskWidgetView:'),
            ('enum WidgetCollection:', '\nstruct TagEntity:'),
            ('enum WidgetTagMatch:', '\nstruct CollectionIntent:'),
            ('extension TaskEntry {', '\nstruct VerveTaskWidget:'),
        ]]
        checks = r'''
let data = #"{"version":1,"revision":1,"locale":"fr","tasks":[{"id":"a","title":"Zulu","completed":false,"dueDate":"2026-10-15","tags":["home"]},{"id":"b","title":"École","completed":false,"dueDate":"2026-10-16","dueTime":"","tags":["home","work"]},{"id":"c","title":"Alpha","completed":false,"dueDate":"2026-10-16","dueTime":"09:00","tags":["work"]},{"id":"d","title":"Future","completed":false,"dueDate":"2026-10-17","tags":[]},{"id":"e","title":"Undated","completed":false,"tags":[]},{"id":"f","title":"Empty date","completed":false,"dueDate":"","tags":[]},{"id":"g","title":"Done","completed":true,"dueDate":"2026-10-16","tags":["home","work"]}]}"#
let snapshot = try JSONDecoder().decode(TaskSnapshot.self, from: Data(data.utf8))
let formatter = DateFormatter()
formatter.dateFormat = "yyyy-MM-dd HH:mm"
let date = formatter.date(from: "2026-10-16 12:00")!
var entry = TaskEntry(date: date, snapshot: snapshot)
func ids() -> [String] { entry.filteredTasks(formatter: formatter).map(\.id) }
assert(ids() == ["a", "c", "b", "d", "e", "f"])
entry.collection = .today
assert(ids() == ["c", "b"])
entry.collection = .upcoming
assert(ids() == ["c", "b", "d"])
entry.collection = .overdue
assert(ids() == ["a"])
entry.collection = .undated
assert(ids() == ["e", "f"])
entry.collection = .all
entry.tagIDs = ["home", "work"]
assert(ids() == ["a", "c", "b"])
entry.tagMatch = .all
assert(ids() == ["b"])
entry.tagIDs = ["deleted-tag"]
assert(ids().isEmpty)
entry.tagIDs = []
assert(ids().count == 6)
entry.tagMatch = .untagged
entry.tagIDs = ["home"]
assert(ids() == ["d", "e", "f"])
entry.collection = .today
assert(ids().isEmpty)
entry.collection = .all
entry.tagMatch = .any
entry.tagIDs = []
entry.sortOrder = .title
assert(ids() == ["c", "b", "f", "d", "e", "a"])
entry.showTagIcons = false
assert(ids().count == 6)
entry.sortOrder = .dueDate
entry.overrides = ["a": true, "g": true]
let checked = entry.filteredTasks(formatter: formatter)
assert(checked.map(\.id) == ["a", "c", "b", "g", "d", "e", "f"])
assert(checked.first { $0.id == "a" }!.completed && checked.first { $0.id == "g" }!.completed)
entry.overrides = ["g": false]
assert(ids().contains("g") && !entry.filteredTasks(formatter: formatter).first { $0.id == "g" }!.completed)
entry.overrides = [:]
entry.date = formatter.date(from: "2026-10-16")!
assert(entry.sectionKey(snapshot.tasks[0]) == "Overdue")
assert(entry.sectionKey(snapshot.tasks[1]) == "Today")
assert(entry.sectionKey(snapshot.tasks[3]) == "Tomorrow")
func task(_ due: String?) -> TaskSnapshot.TaskRow {
    TaskSnapshot.TaskRow(id: due ?? "none", title: "Task", completed: false, dueDate: due, dueTime: nil, tags: [])
}
assert(entry.sectionKey(task("2026-10-18")) == "This week")
assert(entry.sectionKey(task("2026-10-19")) == "Next week")
assert(entry.sectionKey(task("2026-10-25")) == "Next week")
assert(entry.sectionKey(task("2026-10-26")) == "Later")
assert(entry.sectionKey(task(nil)) == "No due date")
entry.date = formatter.date(from: "2026-10-18")!
assert(entry.sectionKey(task("2026-10-19")) == "Tomorrow")
assert(entry.sectionKey(task("2026-10-20")) == "Next week")
entry.date = formatter.date(from: "2026-10-19")!
assert(entry.sectionKey(task("2026-10-25")) == "This week")
assert(entry.sectionKey(task("2026-10-26")) == "Next week")
entry.date = formatter.date(from: "2026-12-31")!
assert(entry.sectionKey(task("2027-01-01")) == "Tomorrow")
let list = [task("2026-12-30"), task("2026-12-31"), task("2027-01-01"), task(nil)]
assert(entry.visibleRows(tasks: list, height: 79).isEmpty)
let rows = entry.visibleRows(tasks: list, height: 160)
assert(rows.count == 4 && rows.last?.task?.id == "2026-12-31")
assert(entry.visibleRows(tasks: list, height: 200, singleTask: true).compactMap(\.task).count == 1)
entry.dateSeparators = .none
assert(entry.visibleRows(tasks: list, height: 162).compactMap(\.task).count == 3)
entry.dateSeparators = .date
assert(entry.visibleRows(tasks: list, height: 500).filter { $0.task == nil }.map(\.id) == ["2026-12-30", "2026-12-31", "2027-01-01", "No due date"])
for mode in [WidgetDateSeparators.relative, .date, .none] {
    entry.dateSeparators = mode
    for height: CGFloat in [80, 134, 160, 280] {
        for single in [false, true] {
            let pages = entry.taskPages(tasks: list, height: height, singleTask: single)
            assert(pages.flatMap { $0.compactMap(\.task).map(\.id) } == list.map(\.id))
            for page in pages {
                assert(page.last?.task != nil)
                assert(page.reduce(0) { $0 + ($1.task == nil ? 26 : 54) } <= Int(height))
                if mode != .none { assert(page.first?.task == nil) }
                if single { assert(page.compactMap(\.task).count == 1) }
            }
        }
    }
}
entry.dateSeparators = .relative
let sameDay = (0..<19).map { i in TaskSnapshot.TaskRow(id: "task-\(i)", title: "Task", completed: false, dueDate: "2026-12-31", dueTime: nil, tags: []) }
let pages = entry.taskPages(tasks: sameDay, height: 160)
assert(pages.count == 10)
assert(pages.flatMap { $0.compactMap(\.task).map(\.id) } == sameDay.map(\.id))
assert(pages.allSatisfy { $0.first?.id == "Today" && $0.first?.count == 19 })
assert(entry.taskPages(tasks: [], height: 160).isEmpty)
assert(entry.taskPages(tasks: sameDay, height: 68, singleTask: true, rowHeight: 48, headingHeight: 20).count == 19)
assert(WidgetPageStore.clamped(99, count: 2) == 1)
assert(WidgetPageStore.clamped(-1, count: 2) == 0)
assert(WidgetPageStore.clamped(8, count: 0) == 0)
let suite = "widget-pagination-test-" + UUID().uuidString
let defaults = UserDefaults(suiteName: suite)!
defer { defaults.removePersistentDomain(forName: suite) }
let key = WidgetPageStore.key(["all", "relative", "systemLarge"])
assert(key.count == 76)
assert(key != WidgetPageStore.key(["today", "relative", "systemLarge"]))
assert(WidgetPageStore.key(["a", "bc"]) != WidgetPageStore.key(["ab", "c"]))
WidgetPageStore.write(3, key: key, defaults: defaults)
WidgetPageStore.write(3, key: key, defaults: defaults)
assert(WidgetPageStore.read(key, defaults: defaults) == 3)
WidgetPageStore.write(-1, key: key, defaults: defaults)
assert(WidgetPageStore.read(key, defaults: defaults) == 0)
entry.collapsedGroups = ["Today"]
let collapsedPages = entry.taskPages(tasks: sameDay, height: 160)
assert(collapsedPages.count == 1 && collapsedPages[0].count == 1)
assert(collapsedPages[0][0].id == "Today" && collapsedPages[0][0].count == 19)
for single in [false, true] {
    let mixed = entry.taskPages(tasks: list, height: 80, singleTask: single)
    assert(mixed.flatMap { $0.compactMap(\.task).map(\.id) } == ["2026-12-30", "2027-01-01", "none"])
    assert(mixed.allSatisfy { $0.reduce(0) { $0 + ($1.task == nil ? 26 : 54) } <= 80 })
}
entry.collapsedGroups = []
assert(entry.taskPages(tasks: sameDay, height: 160).flatMap { $0.compactMap(\.task) }.count == 19)
entry.dateSeparators = .date
entry.collapsedGroups = ["2026-12-31"]
assert(entry.taskPages(tasks: sameDay, height: 160)[0].count == 1)
entry.dateSeparators = .relative
entry.collapsedGroups = Set(list.map { entry.sectionKey($0) })
for single in [false, true] {
    let allCollapsed = entry.taskPages(tasks: list, height: 80, singleTask: single)
    assert(allCollapsed.flatMap { $0 }.count == 4)
    assert(allCollapsed.allSatisfy { $0.count * 26 <= 80 })
    assert(allCollapsed.flatMap { $0 }.allSatisfy { $0.task == nil })
}
entry.dateSeparators = .none
assert(entry.taskPages(tasks: list, height: 160).flatMap { $0.compactMap(\.task) }.count == 4)
WidgetPageStore.write(8, key: key, defaults: defaults)
WidgetPageStore.setCollapsed(true, group: "Today", key: key, defaults: defaults)
WidgetPageStore.setCollapsed(true, group: "Today", key: key, defaults: defaults)
assert(WidgetPageStore.collapsedGroups(key, defaults: defaults) == ["Today"])
assert(WidgetPageStore.read(key, defaults: defaults) == 0)
assert(WidgetPageStore.collapsedGroups(WidgetPageStore.key(["other"]), defaults: defaults).isEmpty)
WidgetPageStore.setCollapsed(false, group: "Today", key: key, defaults: defaults)
assert(WidgetPageStore.collapsedGroups(key, defaults: defaults).isEmpty)
print("PASS: collapse pagination, all-collapsed headers, ungrouped tasks, persistence and retry safety")
print("PASS: pagination coverage, boundaries, repeated headings, compact layout and persistence")
print("PASS: grouping boundaries, row budgets, separator modes; date boundaries, tag any/all/untagged, missing tags, completion exclusion, sorting")
'''
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'FilterTests.swift'
            path.write_text('import CryptoKit\nimport Foundation\nimport OSLog\nimport WidgetKit\nimport SwiftUI\nimport AppIntents\n' + '\n'.join(pieces) + checks)
            subprocess.run(['swift', '-module-cache-path', str(ROOT / 'build/widget-diagnostics/cache'), str(path)], check=True)

    def test_translations(self):
        resources = ROOT / 'native/macos/Resources'
        labels = []
        for locale in ('en', 'fr'):
            text = (resources / f'{locale}.lproj/Localizable.strings').read_text()
            labels.append(set(re.findall(r'^"([^"]+)"\s*=', text, re.M)))
        self.assertEqual(*labels)
        for label in ('Additional tags', 'Tags', 'Tag matching', 'Overdue', 'No due date',
                      'Any selected tag', 'All selected tags', 'Untagged tasks',
                      'Sort order', 'Due date', 'Title', 'Show tag icons',
                      'Previous page', 'Next page', 'Page %lld of %lld',
                      'Expand or collapse group', 'Group', 'Collapsed', 'Expanded', 'Expand group', 'Collapse group'):
            self.assertIn(label, labels[0])


if __name__ == '__main__':
    unittest.main()
