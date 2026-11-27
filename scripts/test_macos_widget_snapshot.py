"""Exercise the production Swift snapshot reader, including unavailable vs empty data."""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class SnapshotReaderTest(unittest.TestCase):
    def test_snapshot_contract(self):
        source = (ROOT / 'native/macos/Sources/Widgets/VerveWidgets.swift').read_text()
        reader = source[source.index('struct TaskSnapshot:'):source.index('\nstruct CompleteTaskIntent:')]
        checks = r'''
let root = URL(fileURLWithPath: CommandLine.arguments[1])
let path = root.appendingPathComponent("snapshot.json")
let now = Date(timeIntervalSince1970: 2000)
assert(TaskSnapshot.syncOverdue(in: root, now: now))
let stamp = root.appendingPathComponent("last-confirmed")
try Data("1100000".utf8).write(to: stamp)
assert(TaskSnapshot.syncOverdue(in: root, now: now))
try Data("1999000".utf8).write(to: stamp)
assert(!TaskSnapshot.syncOverdue(in: root, now: now))
assert(!TaskSnapshot.read(from: root).isAvailable)
try Data("invalid".utf8).write(to: path)
assert(!TaskSnapshot.read(from: root).isAvailable)
try Data(#"{"version":2,"revision":1,"locale":"en","tasks":[]}"#.utf8).write(to: path)
assert(!TaskSnapshot.read(from: root).isAvailable)
try Data(#"{"version":1,"revision":2,"locale":"fr","tasks":[],"tags":[]}"#.utf8).write(to: path)
let empty = TaskSnapshot.read(from: root)
assert(empty.isAvailable && empty.tasks.isEmpty && empty.locale == "fr")
try Data(#"{"version":1,"revision":3,"locale":"fr","tasks":[{"id":"task-identity-0001","title":"Préparer demain","completed":false,"dueDate":"","tags":[]}],"tags":[{"id":"tag-1","name":"Maison"}]}"#.utf8).write(to: path)
let populated = TaskSnapshot.read(from: root)
assert(populated.isAvailable && populated.tasks.count == 1 && populated.revision == 3)
assert(populated.tasks[0].title == "Préparer demain" && !populated.tasks[0].completed)
assert(populated.tags?[0].icon == nil)
try Data(repeating: 32, count: 4 * 1024 * 1024 + 1).write(to: path)
assert(!TaskSnapshot.read(from: root).isAvailable)
print("PASS: missing/corrupt/unsupported/oversized snapshots differ from empty; populated snapshots decode")
'''
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            swift = directory / 'ReaderTests.swift'
            swift.write_text('import Foundation\nimport OSLog\n' + reader + checks)
            subprocess.run(['swift', '-module-cache-path', str(ROOT / 'build/widget-diagnostics/cache'),
                            str(swift), temporary], check=True)


if __name__ == '__main__':
    unittest.main()
