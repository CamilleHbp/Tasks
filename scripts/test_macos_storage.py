"""Exercise the production migration without UI or real user data."""
from pathlib import Path
import fcntl
import plistlib
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class StorageTest(unittest.TestCase):
    def test_atomic_migration_and_resident_lock(self):
        source = (ROOT / 'native/macos/Sources/Bridge/Bridge.swift').read_text()
        source = source.replace('@main\n', '')
        runner = r'''
@main
struct Checks {
    @MainActor static func main() throws {
        let root = URL(fileURLWithPath: CommandLine.arguments[1])
        try NativeBridge.copyStorage(from: root.appendingPathComponent("old"), to: root.appendingPathComponent("new"))
    }
}
'''
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            swift = root / 'Migration.swift'
            swift.write_text(source + runner)
            executable = root / 'migration'
            subprocess.run(['swiftc', '-parse-as-library', '-module-cache-path', str(ROOT / 'build/widget-diagnostics/cache'), str(swift), '-o', str(executable)], check=True)
            old = root / 'old'
            old.mkdir()
            (old / 'preview-state.json').write_text('{"fixture":"tasks"}')
            (old / 'sync-v1').mkdir()
            (old / 'sync-v1/replica').write_bytes(b'private replica fixture')
            (old / 'commands').mkdir()
            (old / 'commands/pending.json').write_text('{}')
            with (old / 'desktop.lock').open('w') as lock:
                fcntl.lockf(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                result = subprocess.run([str(executable), temporary], capture_output=True)
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse((root / 'new').exists())
            subprocess.run([str(executable), temporary], check=True)
            for relative in ('preview-state.json', 'sync-v1/replica', 'commands/pending.json'):
                self.assertEqual((old / relative).read_bytes(), (root / 'new' / relative).read_bytes())
            (old / 'preview-state.json').write_text('must not overwrite destination')
            result = subprocess.run([str(executable), temporary], capture_output=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual((root / 'new/preview-state.json').read_text(), '{"fixture":"tasks"}')
            self.assertFalse(list(root.glob('.migration-*')))

    def test_widget_permissions_are_limited_to_projections(self):
        native = ROOT / 'native/macos'
        host = plistlib.loads((native / 'App.entitlements').read_bytes())
        widget = plistlib.loads((native / 'Widget.entitlements').read_bytes())
        self.assertNotIn('com.apple.security.application-groups', host)
        self.assertTrue(widget['com.apple.security.app-sandbox'])
        self.assertEqual(widget['com.apple.security.temporary-exception.files.home-relative-path.read-only'],
                         ['/Library/Application Support/VerveTwoDoWidget/'])
        self.assertEqual(widget['com.apple.security.temporary-exception.files.home-relative-path.read-write'],
                         ['/Library/Application Support/VerveTwoDoWidget/commands/'])


if __name__ == '__main__':
    unittest.main()
