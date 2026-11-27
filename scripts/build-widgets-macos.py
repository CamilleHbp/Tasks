#!/usr/bin/env python3
"""Build VerveTwoDo Widgets for macOS: a menu-bar companion hosting the WidgetKit extension."""
import argparse
import hashlib
import os
import pathlib
import plistlib
import shutil
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE = ROOT / "native/macos"
OUT = ROOT / "build/widgets/macos"
ARCHS = ("arm64", "x86_64")


def run(*args, **kwargs):
    subprocess.run([str(arg) for arg in args], cwd=ROOT, check=True, **kwargs)


def set_version(plist, version, build):
    with plist.open("rb") as stream:
        info = plistlib.load(stream)
    info["CFBundleShortVersionString"], info["CFBundleVersion"] = version, build
    with plist.open("wb") as stream:
        plistlib.dump(info, stream)


def build_helper(work):
    parts = []
    for arch, goarch in zip(ARCHS, ("arm64", "amd64")):
        output = work / f"helper-{arch}"
        env = {**os.environ, "GOWORK": "off", "GOOS": "darwin", "GOARCH": goarch, "CGO_ENABLED": "0",
               "GOPRIVATE": "github.com/CamilleHbp/sync-core"}
        subprocess.run(["go", "build", "-trimpath", "-ldflags=-s -w", "-o", str(output), "."],
                       cwd=ROOT / "native/widget-helper", env=env, check=True)
        parts.append(output)
    helper = work / "vervetwodo-widget-helper"
    run("lipo", "-create", *parts, "-output", helper)
    return helper


def build_companion(work):
    parts = []
    sources = sorted((SOURCE / "Companion/Sources").glob("*.swift"))
    for arch in ARCHS:
        output = work / f"companion-{arch}"
        run("swiftc", "-parse-as-library", "-O", "-target", f"{arch}-apple-macos14.0", "-module-name", "VerveTwoDoWidgets",
            "-module-cache-path", work / "swift-cache", *sources, "-o", output)
        parts.append(output)
    binary = work / "VerveTwoDoWidgets"
    run("lipo", "-create", *parts, "-output", binary)
    return binary


def build_extension(work):
    # Xcode supplies the App Intents metadata the widget's interactive buttons need.
    derived = work / "xcode"
    run("xcodebuild", "-project", SOURCE / "VerveWidgets.xcodeproj", "-scheme", "VerveWidgets", "-configuration", "Release",
        "-derivedDataPath", derived, "-sdk", "macosx", f"ARCHS={' '.join(ARCHS)}", "ONLY_ACTIVE_ARCH=NO",
        "CODE_SIGNING_ALLOWED=NO", "ENABLE_DEBUG_DYLIB=NO", "build")
    extension = derived / "Build/Products/Release/VerveWidgets.appex"
    if not (extension / "Contents/Resources/Metadata.appintents").exists():
        raise RuntimeError("Xcode did not produce the required App Intent metadata.")
    # Category artwork is exported here by the Camille Icons library (see shared-icons.lock.json).
    for icon in sorted((ROOT / "pwa/public/category-icons").glob("*.png")):
        shutil.copyfile(icon, extension / "Contents/Resources" / f"category-{icon.name}")
    return extension


def build_icon(work):
    iconset = work / "AppIcon.iconset"
    iconset.mkdir(exist_ok=True)
    source = ROOT / "pwa/public/icons/app-512.png"
    for size in (16, 32, 128, 256):
        for scale in (1, 2):
            name = f"icon_{size}x{size}{'@2x' if scale == 2 else ''}.png"
            run("sips", "-z", size * scale, size * scale, source, "--out", iconset / name, stdout=subprocess.DEVNULL)
    shutil.copyfile(source, iconset / "icon_512x512.png")
    run("iconutil", "-c", "icns", iconset, "-o", work / "AppIcon.icns")
    return work / "AppIcon.icns"


def main():
    with (SOURCE / "Companion/Info.plist").open("rb") as stream:
        defaults = plistlib.load(stream)
    parser = argparse.ArgumentParser()
    parser.add_argument("--version", default=defaults["CFBundleShortVersionString"])
    parser.add_argument("--build", default=defaults["CFBundleVersion"])
    parser.add_argument("--identity", default="-", help="codesign identity; default is ad-hoc signing")
    parser.add_argument("--install", action="store_true", help="copy to ~/Applications, register the widget and launch")
    args = parser.parse_args()
    if sys.platform != "darwin":
        parser.error("This build requires macOS.")
    shutil.rmtree(OUT, ignore_errors=True)
    work = OUT / "work"
    work.mkdir(parents=True)
    subprocess.run(["go", "test", "./..."], cwd=ROOT / "native/widget-helper", env={**os.environ, "GOWORK": "off"}, check=True)

    app = OUT / "VerveTwoDo Widgets.app"
    contents = app / "Contents"
    (contents / "MacOS").mkdir(parents=True)
    (contents / "PlugIns").mkdir()
    resources = contents / "Resources"
    resources.mkdir()
    shutil.copyfile(build_companion(work), contents / "MacOS/VerveTwoDoWidgets")
    (contents / "MacOS/VerveTwoDoWidgets").chmod(0o755)
    shutil.copyfile(SOURCE / "Companion/Info.plist", contents / "Info.plist")
    set_version(contents / "Info.plist", args.version, args.build)
    for locale in ("en", "fr"):
        shutil.copytree(SOURCE / f"Companion/Resources/{locale}.lproj", resources / f"{locale}.lproj")
    shutil.copyfile(build_icon(work), resources / "AppIcon.icns")
    helper = resources / "vervetwodo-widget-helper"
    shutil.copyfile(build_helper(work), helper)
    helper.chmod(0o755)
    extension = contents / "PlugIns/VerveWidgets.appex"
    shutil.copytree(build_extension(work), extension)
    set_version(extension / "Contents/Info.plist", args.version, args.build)

    run("codesign", "--force", "--sign", args.identity, "--options", "runtime", "--identifier", "studio.camille.vervetwodo.widgets.helper", helper)
    run("codesign", "--force", "--sign", args.identity, "--entitlements", SOURCE / "Widget.entitlements", extension)
    run("codesign", "--force", "--sign", args.identity, app)
    run("codesign", "--verify", "--deep", "--strict", app)

    archive = OUT / f"vervetwodo-widgets-{args.version}-macos-universal.zip"
    run("ditto", "-c", "-k", "--sequesterRsrc", "--keepParent", app, archive)
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    print(f"{archive}\nsize {archive.stat().st_size}\nsha256 {digest}")

    if args.install:
        destination = pathlib.Path.home() / "Applications" / app.name
        subprocess.run(["pkill", "-x", "VerveTwoDoWidgets"])
        shutil.rmtree(destination, ignore_errors=True)
        run("ditto", app, destination)
        run("pluginkit", "-a", destination / "Contents/PlugIns/VerveWidgets.appex")
        run("open", destination)


if __name__ == "__main__":
    main()
