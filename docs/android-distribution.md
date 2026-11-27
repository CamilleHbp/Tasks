# Android downloads

The Android build produces standalone ARM64 (`arm64-v8a`), x86_64, and universal APKs for both debug and release. Each APK installs independently. ARM64 is the primary download; x86_64 supports compatible devices and emulators. Universal APKs preserve the existing download URLs for older in-app updaters.

After changing Android inputs, increment the Android version and run:

```sh
python3 /Users/camille/.codex/scripts/android_build_gate.py build --project "$PWD"
python3 scripts/prepare-android-release.py
```

The preparation script verifies the build receipt, all six APK signatures, native-library compression, architecture contents, and matching versions. It exports immutable artifacts and a `release.json` under `build/releases/<version>-<code>/`.

Publish that specification using the shared publisher:

```sh
python3 /Users/camille/Dev/Personal/HomeServer/apps/downloads/scripts/publish.py /absolute/path/to/release.json
python3 /Users/camille/Dev/Personal/HomeServer/apps/downloads/scripts/verify-http.py
```

Keep `android-release.apk` and `android-debug.apk` as universal compatibility assets. Use `android-arm64-release.apk`, `android-x64-release.apk`, and their debug equivalents for architecture-specific downloads. Only the ARM64 release is primary. Updater 1.0.1 prefers the device's architecture and falls back to universal; older updater versions continue using the original keys.

The Go adapter continues compiling both supported architectures into its AAR with stripped debugging symbols. Gradle selects the appropriate native libraries for each standalone APK.
