# Archived: VerveTwoDo desktop app

The Compose Multiplatform desktop app for macOS and Windows is retired. Tasks on desktop is now the [web app](../../pwa/README.md), and desktop widgets ship separately as Tasks Widgets (`native/`, `scripts/build-widgets-*.py`).

This folder keeps the last source for reference. It is not part of the Gradle build.

| Path | Former location | Contents |
| --- | --- | --- |
| `desktopApp/` | `desktopApp/` | Compose desktop application |
| `desktopIntegration/` | `desktopIntegration/` | Desktop sync integration tests |
| `desktop-helper/` | `desktop-helper/` | Go sync helper bundled with the desktop app |
| `scripts/` | `scripts/` | Desktop packaging, smoke and sync test scripts |
| `docs/desktop-development.md` | `docs/` | Desktop development notes |

The macOS native bridge (`native/macos/Sources/Bridge`) stays in `native/macos` with the widget sources.

To build it again, move the modules back to the repository root and restore `include(":desktopApp", ":desktopIntegration")` in `settings.gradle.kts`.
