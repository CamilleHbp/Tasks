#!/usr/bin/env python3
"""Build Tasks Widgets for Windows 11: a self-signed MSIX widget provider inside a one-step setup program.

On Windows it packs and signs with makeappx and signtool; elsewhere with scripts/msix.py and
osslsigncode. The signing certificate stays outside the repository: VERVE_WIDGETS_PFX, by default
~/Library/Application Support/VerveTwoDo Signing/vervetwodo-widgets.pfx, with its password in
VERVE_WIDGETS_PFX_PASSWORD or the macOS Keychain item "vervetwodo-widgets-signing". Every release
must be signed with the same certificate, or installed copies cannot update themselves.
"""
import argparse
import hashlib
import os
import pathlib
import shutil
import subprocess
import sys
import zipfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import msix  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parents[1]
DEFAULT_PFX = pathlib.Path.home() / "Library/Application Support/VerveTwoDo Signing/vervetwodo-widgets.pfx"
SOURCE = ROOT / "native/windows/Companion"
SETUP = ROOT / "native/windows/Setup"
OUT = ROOT / "build/widgets/windows"
SINGLE_MANIFEST = """#!/usr/bin/env python3
import sys, shutil, xml.etree.ElementTree as ET
a=sys.argv[1:]
assert a[:2] == ['-nologo','-manifest'] and len(a)==4 and a[3].startswith('-out:'), a
ET.parse(a[2]); shutil.copyfile(a[2],a[3][5:])
"""


def run(*args, **kwargs):
    subprocess.run([str(arg) for arg in args], cwd=kwargs.pop("cwd", ROOT), check=True, **kwargs)


def export_certificate(pfx, password, output, form):
    """Write the public certificate only; the private key never leaves the PFX."""
    certificate = subprocess.run(["openssl", "pkcs12", "-in", pfx, "-nokeys", "-clcerts", "-passin", "pass:" + (password or "")],
                                 capture_output=True, check=True).stdout
    subprocess.run(["openssl", "x509", "-outform", form, "-out", output], input=certificate, check=True)
    return output


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--version", required=True, help="three-part version, for example 1.0.0")
    args = parser.parse_args()
    if len(args.version.split(".")) != 3 or not all(p.isdigit() for p in args.version.split(".")):
        parser.error("--version must have three numeric parts")
    shutil.rmtree(OUT, ignore_errors=True)
    layout = OUT / "layout"
    work = OUT / "work"
    work.mkdir(parents=True)

    go = dict(os.environ, GOWORK="off", GOPRIVATE="github.com/CamilleHbp/sync-core")
    run("go", "test", "./...", cwd=ROOT / "native/widget-helper", env=go)
    publish = ["dotnet", "publish", SOURCE / "VerveWidgetsCompanion.csproj", "-c", "Release", "-r", "win-x64",
               "-p:AppxGeneratePriEnabled=false", "-o", layout]
    if sys.platform != "win32":
        # Cross-builds have no Windows manifest tool; keep the SDK-generated manifest as-is.
        tool = work / "single-manifest.py"
        tool.write_text(SINGLE_MANIFEST)
        tool.chmod(0o755)
        publish.insert(-2, "-p:ManifestTool=" + str(tool))
    run(*publish)
    for required in ("VerveWidgetsCompanion.exe", "Microsoft.WindowsAppRuntime.dll", "Microsoft.Windows.Widgets.dll"):
        assert (layout / required).is_file(), required
    run("go", "build", "-trimpath", "-ldflags=-s -w", "-o", layout / "vervetwodo-widget-helper.exe", ".",
        cwd=ROOT / "native/widget-helper", env=dict(go, GOOS="windows", GOARCH="amd64", CGO_ENABLED="0"))
    shutil.copytree(SOURCE / "Assets", layout / "Assets", dirs_exist_ok=True)
    (layout / "Public").mkdir(exist_ok=True)
    manifest = (SOURCE / "Package.appxmanifest").read_text(encoding="utf-8").replace("__VERSION__", args.version)
    (layout / "AppxManifest.xml").write_text(manifest, encoding="utf-8")

    # Widgets Board needs the public folder to exist, and a package cannot hold an empty folder.
    (layout / "Public/widgets.txt").write_text("Tasks Widgets\n", encoding="utf-8")

    pfx = os.environ.get("VERVE_WIDGETS_PFX", str(DEFAULT_PFX))
    if not pathlib.Path(pfx).is_file():
        sys.exit("Set VERVE_WIDGETS_PFX to the release signing certificate (CN=VerveTwoDo Personal).")
    password = os.environ.get("VERVE_WIDGETS_PFX_PASSWORD")
    if password is None and sys.platform == "darwin":
        password = subprocess.run(["security", "find-generic-password", "-s", "vervetwodo-widgets-signing", "-w"],
                                  capture_output=True, text=True).stdout.strip() or None
    package = work / "VerveTwoDoWidgets.msix"
    certificate = work / "VerveTwoDoWidgets.cer"
    if sys.platform == "win32":
        run("makeappx", "pack", "/o", "/d", layout, "/p", package)
        sign = ["signtool", "sign", "/fd", "SHA256", "/f", pfx] + (["/p", password] if password else [])
        run(*sign, package)
        run("powershell", "-NoProfile", "-Command",
            f"$c = Get-PfxCertificate -FilePath '{pfx}'; Export-Certificate -Cert $c -FilePath '{certificate}' | Out-Null")
    else:
        unsigned = work / "unsigned.msix"
        msix.pack(layout, unsigned)
        msix.verify(unsigned)
        run("osslsigncode", "sign", "-pkcs12", pfx, *(["-pass", password] if password else []),
            "-h", "sha256", "-in", unsigned, "-out", package)
        run("osslsigncode", "verify", "-CAfile", export_certificate(pfx, password, work / "VerveTwoDoWidgets.pem", "PEM"),
            "-in", package)
        export_certificate(pfx, password, certificate, "DER")
        unsigned.unlink()
    msix.verify(package)

    # The setup program carries the package and its certificate, so installing is one download.
    payload = work / "payload.zip"
    with zipfile.ZipFile(payload, "w", zipfile.ZIP_DEFLATED) as bundle:
        bundle.write(package, package.name)
        bundle.write(certificate, certificate.name)
    setup = work / "setup"
    run("dotnet", "publish", SETUP / "VerveWidgetsSetup.csproj", "-c", "Release", "-p:Payload=" + str(payload),
        f"-p:Version={args.version}", "-o", setup)
    unsigned = setup / "VerveTwoDoWidgetsSetup.exe"
    installer = OUT / f"vervetwodo-widgets-{args.version}-windows-x64.exe"
    if sys.platform == "win32":
        shutil.copyfile(unsigned, installer)
        run(*(["signtool", "sign", "/fd", "SHA256", "/f", pfx] + (["/p", password] if password else [])), installer)
    else:
        run("osslsigncode", "sign", "-pkcs12", pfx, *(["-pass", password] if password else []),
            "-h", "sha256", "-n", "Tasks Widgets Setup", "-in", unsigned, "-out", installer)
    print(f"{installer}\nsize {installer.stat().st_size}\nsha256 {hashlib.sha256(installer.read_bytes()).hexdigest()}")


if __name__ == "__main__":
    main()
