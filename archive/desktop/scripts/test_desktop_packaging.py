"""Unit checks for the packaged desktop sync helper (no UI or network)."""
import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("macos_build", Path(__file__).with_name("build-desktop-macos.py"))
build = importlib.util.module_from_spec(spec)
spec.loader.exec_module(build)


class PackagedHelperTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.app = self.root / "VerveTwoDo.app"
        self.helper = self.app / "Contents/app/resources/vervetwodo-helper"
        self.built = self.root / "desktopApp/build/generated/resources/common/vervetwodo-helper"
        for path in (self.helper, self.built):
            path.parent.mkdir(parents=True)
            path.write_bytes(b"current helper")
        self.root_patch = patch.object(build, "ROOT", self.root)
        self.root_patch.start()
        self.addCleanup(self.root_patch.stop)

    def metadata(self, version):
        return patch.object(build.subprocess, "run", side_effect=[
            subprocess.CompletedProcess([], 0, stdout="v0.2.0\n"),
            subprocess.CompletedProcess([], 0, stdout=f"\tdep\tgithub.com/CamilleHbp/sync-core\t{version}\th1:fixture\n"),
        ])

    def test_current_package(self):
        with self.metadata("v0.2.0"):
            build.verify_packaged_helper(self.app)

    def test_stale_packaged_binary(self):
        self.helper.write_bytes(b"old helper")
        with self.assertRaisesRegex(RuntimeError, "differs from"):
            build.verify_packaged_helper(self.app)

    def test_stale_dependency(self):
        with self.metadata("v0.1.0"), self.assertRaisesRegex(RuntimeError, "dependency pin"):
            build.verify_packaged_helper(self.app)


class SkikoPackagingTest(unittest.TestCase):
    def setUp(self):
        architecture = patch.object(build.platform, "machine", return_value="arm64")
        architecture.start()
        self.addCleanup(architecture.stop)
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.app = Path(self.temp.name) / "VerveTwoDo.app"
        self.libraries = self.app / "Contents/app"
        self.libraries.mkdir(parents=True)
        self.config = self.libraries / "VerveTwoDo.cfg"
        self.config.write_text("java-options=-Dskiko.library.path=$APPDIR\n")
        self.native = self.libraries / "libskiko-macos-arm64.dylib"
        self.native.write_bytes(b"native code")
        self.archive = self.libraries / "skiko-awt-runtime-macos-arm64-test.jar"
        with zipfile.ZipFile(self.archive, "w") as archive:
            archive.writestr(self.native.name, b"native code")
            archive.writestr(self.native.name + ".sha256", b"sdk checksum")
            archive.writestr("libskiko-macos-x64.dylib", b"unused Intel code")
            archive.writestr("META-INF/LICENSE", b"library attribution")

    def test_keeps_native_file_and_attribution(self):
        build.remove_duplicate_skiko(self.app)
        self.assertEqual(self.native.read_bytes(), b"native code")
        with zipfile.ZipFile(self.archive) as archive:
            self.assertNotIn(self.native.name, archive.namelist())
            self.assertEqual(archive.read("META-INF/LICENSE"), b"library attribution")
            self.assertNotIn("libskiko-macos-x64.dylib", archive.namelist())

    def test_refuses_missing_extracted_library(self):
        self.native.unlink()
        with self.assertRaisesRegex(RuntimeError, "Missing extracted"):
            build.remove_duplicate_skiko(self.app)
        with zipfile.ZipFile(self.archive) as archive:
            self.assertIn(self.native.name, archive.namelist())

    def test_refuses_launcher_without_native_path(self):
        self.config.write_text("")
        with self.assertRaisesRegex(RuntimeError, "native-library path"):
            build.remove_duplicate_skiko(self.app)


if __name__ == "__main__":
    unittest.main()
