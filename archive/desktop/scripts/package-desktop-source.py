#!/usr/bin/env python3
"""Create a portable desktop source bundle without Android data or local credentials."""
from pathlib import Path
import zipfile

root = Path(__file__).resolve().parents[1]
output = root / "build/VerveTwoDo-desktop-source.zip"
files = [root / name for name in (
    "gradlew", "gradlew.bat", "gradle.properties", "settings.gradle.kts", "build.gradle.kts",
    "docs/desktop-development.md", "scripts/test-desktop-windows.ps1",
    "scripts/build-desktop-macos.py", "scripts/package-desktop-source.py",
    "scripts/smoke-desktop.py",
)]
for folder in ("gradle", "desktopApp", "desktopIntegration", "desktop-helper", "shared", "sync-engine", "native"):
    files.extend(path for path in (root / folder).rglob("*") if path.is_file()
                 and not {"build", "bin", "obj", ".gradle", "xcuserdata"}.intersection(path.relative_to(root).parts))
output.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
    for path in sorted(files):
        archive.write(path, "VerveTwoDo-desktop/" + path.relative_to(root).as_posix())
print(output)
