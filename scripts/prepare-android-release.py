#!/usr/bin/env python3
"""Verify all ABI APKs and prepare an immutable download-site release specification."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
from datetime import datetime
from zoneinfo import ZoneInfo
import zipfile

ROOT = Path(__file__).resolve().parents[1]
APP = "vervetwodo"
MODULE = "app"
ARCHITECTURES = {"arm64-v8a": "arm64", "x86_64": "x64", None: "universal"}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    gate = Path.home() / ".codex/scripts/android_build_gate.py"
    subprocess.run(["python3", str(gate), "verify", "--project", str(ROOT)], check=True)
    receipt = json.loads((ROOT / ".gradle/codex-apk/receipt.json").read_text())
    verified = {str((ROOT / item["path"]).resolve()): item["sha256"] for item in receipt["apks"]}
    sdk = Path(os.environ.get("ANDROID_HOME", Path.home() / "Library/Android/sdk"))
    signers = list(sdk.glob("build-tools/*/apksigner"))
    if not signers:
        raise RuntimeError("Android SDK apksigner is required")
    signer = max(signers, key=lambda p: tuple(int(n) for n in p.parent.name.split(".") if n.isdigit()))
    pending = []
    versions = set()
    for channel in ("release", "debug"):
        directory = ROOT / MODULE / "build/outputs/apk" / channel
        metadata = json.loads((directory / "output-metadata.json").read_text())
        if metadata["applicationId"] != "studio.camille." + APP or metadata["variantName"] != channel:
            raise RuntimeError("Unexpected app or build variant")
        found = set()
        for element in metadata["elements"]:
            filters = element.get("filters", [])
            if any(f["filterType"] != "ABI" for f in filters) or len(filters) > 1:
                raise RuntimeError("Unexpected APK filters")
            abi = filters[0]["value"] if filters else None
            if abi not in ARCHITECTURES or abi in found:
                raise RuntimeError("Unexpected or duplicate APK architecture")
            found.add(abi)
            apk = directory / element["outputFile"]
            checksum = digest(apk)
            if verified.get(str(apk.resolve())) != checksum:
                raise RuntimeError(f"APK not covered by the fresh build receipt: {apk}")
            with zipfile.ZipFile(apk) as archive:
                if archive.testzip() is not None:
                    raise RuntimeError(f"Corrupt APK: {apk}")
                natives = [x for x in archive.infolist() if x.filename.startswith("lib/") and x.filename.endswith(".so")]
                cpus = {x.filename.split("/")[1] for x in natives}
                sync_cpus = {x.filename.split("/")[1] for x in natives if x.filename.endswith("/libgojni.so")}
                expected = {abi} if abi else {"arm64-v8a", "x86_64"}
                if sync_cpus != expected or (abi and cpus != expected):
                    raise RuntimeError(f"Wrong native libraries in {apk}: {cpus}, sync={sync_cpus}")
                if any(x.compress_type != zipfile.ZIP_DEFLATED for x in natives):
                    raise RuntimeError(f"Uncompressed native library in {apk}")
            subprocess.run([str(signer), "verify", str(apk)], check=True)
            version, code = element["versionName"], element["versionCode"]
            versions.add((version, code))
            arch = ARCHITECTURES[abi]
            # Preserve old universal URL keys for installations using updater 1.0.0.
            key = f"android-{channel}.apk" if abi is None else f"android-{arch}-{channel}.apk"
            pending.append((apk, checksum, version, code, arch, channel, key))
        if found != set(ARCHITECTURES):
            raise RuntimeError(f"Missing APK architectures for {channel}: {found}")
    if len(versions) != 1:
        raise RuntimeError(f"APK versions differ: {versions}")
    version, code = versions.pop()
    destination = ROOT / "build/releases" / f"{version}-{code}"
    destination.mkdir(parents=True, exist_ok=True)
    assets = []
    for apk, checksum, _, _, arch, channel, key in pending:
        suffix = "-debug" if channel == "debug" else ""
        output = destination / f"tasks-{version}-{arch}{suffix}.apk"
        if output.exists() and digest(output) != checksum:
            raise RuntimeError(f"Refusing to replace immutable artifact: {output}")
        if not output.exists():
            shutil.copy2(apk, output)
        assets.append({"path": str(output), "name": key, "platform": "android", "channel": channel,
                       "label": "Android · " + {"arm64": "ARM64", "x64": "x86_64", "universal": "Universal"}[arch]
                                + (" · Debug" if channel == "debug" else ""),
                       "primary": arch == "arm64" and channel == "release", "sha256": checksum})
    spec = {"app": APP, "version": version, "id": f"{version}-{code}",
            "date": datetime.now(ZoneInfo("Europe/Paris")).date().isoformat(), "assets": assets}
    manifest = destination / "release.json"
    if manifest.exists():
        existing = json.loads(manifest.read_text())
        spec["date"] = existing["date"]
        if existing != spec:
            raise RuntimeError(f"Refusing to replace immutable release specification: {manifest}")
    else:
        manifest.write_text(json.dumps(spec, indent=2) + "\n")
    print(manifest)


if __name__ == "__main__":
    main()
