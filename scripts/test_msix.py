import pathlib
import tempfile
import unittest
import zipfile

import msix


class PackTest(unittest.TestCase):
    def layout(self, root):
        (root / "Assets").mkdir(parents=True)
        (root / "AppxManifest.xml").write_text("<Package/>")
        (root / "Assets/Logo.png").write_bytes(bytes(range(256)) * 600)
        (root / "App.exe").write_bytes(b"")
        return root

    def test_block_map_covers_every_payload_block(self):
        with tempfile.TemporaryDirectory() as directory:
            root = self.layout(pathlib.Path(directory) / "layout")
            package = msix.pack(root, pathlib.Path(directory) / "out.msix")
            self.assertEqual(msix.verify(package), 3)
            with zipfile.ZipFile(package) as archive:
                names = [info.filename for info in archive.infolist()]
                self.assertEqual(names[-2:], ["AppxBlockMap.xml", "[Content_Types].xml"])
                self.assertEqual(archive.getinfo("Assets/Logo.png").compress_type, zipfile.ZIP_STORED)
                self.assertEqual(archive.getinfo("[Content_Types].xml").compress_type, zipfile.ZIP_DEFLATED)
                self.assertEqual(archive.read("AppxBlockMap.xml").count(b"<Block "), 4)

    def test_tampered_payload_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            package = msix.pack(self.layout(pathlib.Path(directory) / "layout"), pathlib.Path(directory) / "out.msix")
            data = bytearray(package.read_bytes())
            index = data.index(bytes(range(256)))
            data[index] ^= 1
            package.write_bytes(data)
            with self.assertRaises(Exception):
                msix.verify(package)

    def test_rejects_names_windows_cannot_install(self):
        with tempfile.TemporaryDirectory() as directory:
            root = self.layout(pathlib.Path(directory) / "layout")
            (root / "with space.dll").write_bytes(b"x")
            with self.assertRaises(ValueError):
                msix.pack(root, pathlib.Path(directory) / "out.msix")


if __name__ == "__main__":
    unittest.main()
