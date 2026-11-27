#!/usr/bin/env python3
"""Build VerveTwoDo for macOS. Widgets ship separately in VerveTwoDo Widgets (scripts/build-widgets-macos.py)."""
import argparse
import hashlib
import os
import pathlib
import platform
import shutil
import subprocess
import sys
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]


def run(*args):
    subprocess.run([str(arg) for arg in args], cwd=ROOT, check=True)



def verify_packaged_helper(app):
    """Refuse a distributable whose native sync engine is stale."""
    helper = app / "Contents/app/resources/vervetwodo-helper"
    built = ROOT / "desktopApp/build/generated/resources/common/vervetwodo-helper"
    if hashlib.sha256(helper.read_bytes()).digest() != hashlib.sha256(built.read_bytes()).digest():
        raise RuntimeError("Packaged sync helper differs from the freshly built helper")
    module = "github.com/CamilleHbp/sync-core"
    expected = subprocess.run(
        ["go", "list", "-m", "-f", "{{.Version}}", module], cwd=ROOT / "desktop-helper",
        env=dict(os.environ, GOWORK="off", GOPRIVATE=module),
        capture_output=True, text=True, check=True).stdout.strip()
    metadata = subprocess.run(["go", "version", "-m", str(helper)],
                              capture_output=True, text=True, check=True).stdout
    if not expected or f"\tdep\t{module}\t{expected}\t" not in metadata:
        raise RuntimeError("Packaged sync-core does not match the desktop dependency pin")


def remove_duplicate_skiko(app):
    """Keep jpackage's host library and omit unused native JAR fallbacks."""
    libraries = app / "Contents/app"
    launcher = (libraries / "VerveTwoDo.cfg").read_text()
    if "java-options=-Dskiko.library.path=$APPDIR" not in launcher:
        raise RuntimeError("Cannot deduplicate Skiko without the packaged native-library path")
    architecture = {"arm64": "arm64", "x86_64": "x64"}[platform.machine()]
    extracted = libraries / f"libskiko-macos-{architecture}.dylib"
    if not extracted.is_file():
        raise RuntimeError(f"Missing extracted Skiko library: {extracted}")
    for archive in libraries.glob("skiko-awt-runtime-macos-*.jar"):
        temporary = archive.with_suffix(".tmp")
        with zipfile.ZipFile(archive) as source, zipfile.ZipFile(
                temporary, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as destination:
            for entry in source.infolist():
                if entry.filename.startswith("libskiko-") and entry.filename.endswith((".dylib", ".dylib.sha256")):
                    # Some runtime JARs include both Intel and ARM libraries. jpackage
                    # extracts the host one, but leaves the unused architecture in the JAR.
                    continue
                destination.writestr(entry, source.read(entry))
        temporary.replace(archive)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--identity", default="-", help="codesign identity; default is ad-hoc local signing")
    parser.add_argument("--install", action="store_true", help="copy VerveTwoDo to ~/Applications")
    args = parser.parse_args()
    if sys.platform != "darwin":
        parser.error("This build requires macOS.")
    run(ROOT / "gradlew", "-PdesktopOnly=true", ":desktopApp:clean", ":desktopIntegration:test", ":desktopApp:createDistributable", "--console=plain")
    app = ROOT / "desktopApp/build/compose/binaries/main/app/VerveTwoDo.app"
    if not app.is_dir():
        raise RuntimeError(f"Application was not packaged: {app}")
    verify_packaged_helper(app)
    remove_duplicate_skiko(app)
    source = ROOT / "native/macos"
    for locale in ("en", "fr"):
        shutil.copytree(source / f"Resources/{locale}.lproj", app / f"Contents/Resources/{locale}.lproj", dirs_exist_ok=True)
    for helper in ("VerveNative", "vervetwodo-helper"):
        (app / "Contents/app/resources" / helper).chmod(0o755)
    native = app / "Contents/MacOS/VerveNative"
    run("codesign", "--force", "--sign", args.identity, app / "Contents/app/resources/libVerveNotifications.dylib")
    run("codesign", "--force", "--sign", args.identity, "--identifier", "studio.camille.vervetwodo.preview", "--entitlements", source / "App.entitlements", native)
    run("codesign", "--force", "--sign", args.identity, "--entitlements", source / "App.entitlements", app)
    run("codesign", "--verify", "--deep", "--strict", app)
    if args.install:
        destination = pathlib.Path.home() / "Applications/VerveTwoDo.app"
        if destination.exists():
            raise RuntimeError(f"Quit and move the previous installation before installing: {destination}")
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copytree(app, destination)
        run("/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister", "-f", destination)
        print(destination)
    else:
        print(app)


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, subprocess.CalledProcessError) as error:
        print(f"macOS desktop build blocked: {error}", file=sys.stderr)
        sys.exit(1)
