# Desktop development

The desktop app has its own application identity, URL scheme, and local data directory. Android and desktop exchange task records through the independently versioned `github.com/CamilleHbp/sync-core` Go module, pinned in `sync-engine/go.mod` and `desktop-helper/go.mod`. The `sync-engine` module contains VerveTwoDo's schema, gomobile adapter, and application server; it does not contain a copy of the synchronization engine.

## Connect Android and macOS

Open **Settings → Synchronization** on both devices. Use the same server address and access code. See the [self-hosting guide](https://github.com/CamilleHbp/sync-core/blob/main/docs/self-hosting.md) to set up your own workspace.

The current service provides one private owner workspace for personal device synchronization. Existing tasks are added on first connection. Tasks, tag colors, checklists, completion, recurrence, and scheduling fields synchronize; appearance, favorites, widget configuration, and reminder preferences stay on each device. Independent field edits merge; competing field values are listed in Synchronization for explicit resolution. Deletion remains a synchronized marker.

Android retains its integer task IDs for navigation and widgets, with UUIDs for synchronization. Room edits commit with a revision marker. Causal operations are journaled before the Go engine accepts them durably; incoming projections require an unchanged local revision. Both applications use persistent upstream any-sync streams on the private network, with change notifications and connectivity/resume recovery rather than an application synchronization timer. Upstream anti-entropy remains enabled. Desktop synchronizes while resident, including after closing its window; explicit Quit stops it. Mobile delivery depends on Android allowing the process to run. Native status does not expose an exact pending-delivery count. Enter the access code again when provisioning access needs renewal, without replacing the local replica.

Shared workspace invitations, runtime membership changes, and collaborator alerts require a subsequent application-server and UI implementation. The initial owner workspace is separate from Groceries, with independent credentials and storage.

Private Go dependencies require GitHub repository access on build machines. If HTTPS credential helpers are unavailable, configure Git to use your GitHub SSH identity for `https://github.com/CamilleHbp/sync-core`. The build scripts set `GOPRIVATE` for this module and disable workspace overrides. Android builds require NDK `29.0.14206865` (or an explicit `ANDROID_NDK_HOME`).

Deploy the application service with `python3 scripts/deploy-sync-server.py`. It creates an isolated container and dataset, validates the added Caddy route, and backs up the previous proxy configuration. Credentials and application data are never part of the image.

## Local task features

Agenda groups tasks by calendar date; Tags opens a collection for each tag. Both task collections support search, status filters, six sort orders, multi-selection, bulk editing, and deletion with Undo. The shared task modal provides viewing, creation, and editing for notes, ordered checklists, colored tags, five priority levels, calendar dates, explicit time zones for timed tasks, and daily, weekly, monthly, or yearly recurrence. Monthly and yearly rules preserve their original calendar anchor.

Creating, viewing, and editing tasks use the same native modal up to 1000 × 760 dp, bounded to 92% of the available width and 90% of the available height. At a content width of at least 840 dp, the task content and 360 dp metadata column scroll independently; narrower layouts use one scrolling column. The header and action footer stay visible. View mode displays selectable titles and rendered Markdown notes, read-only checklist items and completion state, and Edit and Delete actions. Checklist accessibility descriptions include localized completion state. Edit switches to editable fields in the same modal; saving or discarding an existing-task draft returns to view mode. Command-Enter or Control-Enter saves a draft. Cancel, Close, and Escape while editing open the discard confirmation; Close and Escape in view mode dismiss immediately. Clicking outside leaves the modal open. Validation and save-operation errors appear inside the modal. All labels, states, and validation use the existing English and French resources.

Completing a recurring task creates one deterministic successor and resets its checklist. Undo retracts an untouched successor and preserves subsequent edits. An editor refuses to overwrite a task changed through another action while its draft is open.

The File menu saves a desktop JSON backup, exports CSV, and imports desktop JSON backups as new tasks. Existing tasks remain intact; importing a file twice creates two copies. Android ZIP backups are not yet supported by this desktop importer. Language and light/dark appearance overrides stay on the device. Command-N opens task creation and Command-F focuses search on macOS.

## macOS

Prerequisites: JDK 21 or newer, Go 1.27, and full Xcode selected with `xcode-select`. No paid developer membership is required to compile the sources. Local ad-hoc signing is the default. The sandboxed widget has read-only access to the dedicated widget projection directory and read/write access to its commands subdirectory. App Groups are not required.

```sh
python3 scripts/build-desktop-macos.py
```

The output is `desktopApp/build/compose/binaries/main/app/VerveTwoDo.app`. Use `--install` to copy it to `~/Applications` and register the widget. An existing installation must be quit and moved before replacement. The build verifies that the packaged Go helper matches both the generated helper and the pinned sync-core version. A configured development signing identity can be supplied with `--identity`.

The visible application name is VerveTwoDo. The existing bundle ID, URL scheme and preferences remain unchanged. Existing App Group storage is copied through a one-time system folder picker, retaining local tasks and synchronization state. Canceling the transfer stops startup without creating an empty replacement store. Replace the installed app bundle after rebuilding; rebuilding sources alone does not update `/Applications`.

Run the desktop UI without extension packaging:

```sh
./gradlew -PdesktopOnly=true :desktopApp:run
```

Native notifications require the packaged app. The app retains a notification delegate and requests banners, Notification Center entries, and sound even while its task window is active. macOS notification settings and Focus still control presentation. To check this behavior, keep the packaged app in the foreground and use **Test notification**. If only a Notification Center entry appears, check VerveTwoDo under **System Settings → Notifications** and allow banners or alerts.

Task reminders are enabled by default. Settings lets you change the default reminder time (initially 09:00). Timed tasks use their explicit time zone; date-only tasks use the default time in the device time zone. Undated tasks do not generate reminders. The app schedules the next 60 notifications and refreshes them after edits and while resident. Scheduled notifications may arrive after Quit; opening the app is required to refresh the queue. Completing, deleting, or rescheduling a task cancels its obsolete pending notifications. Clicking a reminder opens its task.

The widget gallery offers one configurable **Tasks** widget. Right-click it and choose **Edit Widget**. Each instance remembers its own date collection (all, today, upcoming, overdue, or undated), tag selection, any/all tag matching or untagged-only filter, due-date/title sorting, tag-icon visibility, and date separators (relative dates, calendar dates, or none). Relative separators group tasks into Overdue, Today, Tomorrow, This week, Next week, Later, and No due date; sorting applies within each date group. Next week ends on Sunday. The widget fits complete rows and headings to its size. Previous and Next arrows and a page indicator let you browse every matching task without opening the app. Click a date heading to collapse or expand its tasks; the heading and task count remain visible. Collapsed groups are remembered for each widget configuration, and changing a group returns to the first page. Date headings repeat when an expanded group continues onto another page. Small widgets show one task per page, with the background opening the displayed task. Navigation stays within the first and last pages and adjusts when tasks are completed or removed. Widgets with identical filters, sorting, grouping, icon visibility, and size share their remembered page; changing those settings uses a separate page position. Click a task title to open its details in the desktop app, then choose Edit. Existing editor drafts are retained; a requested task opens after the draft is saved or discarded. With no selected tags, any/all matching includes every tag. The Tasks widget retains its original Tag selector and adds Additional tags; matching applies to all selected tags together. Widget completions use the same task rules as the app, including recurrence. English and French labels follow the app's language in widget content; system configuration sheets follow macOS.

## Windows 11

Prerequisites: Windows 11 x64, JDK 21 or newer, Go 1.27, and the .NET 8 SDK or newer. Copy the checkout or extract the desktop source ZIP, then open PowerShell in its root directory. The Windows App SDK packages are restored by .NET; an Android SDK is not required.

```powershell
./scripts/test-desktop-windows.ps1 -Install
```

`-Install` registers a development package for the current user and requires Windows Developer Mode. It does not create or trust a certificate. Omit the flag to only compile, package, and run protocol checks. Reinstallation closes the previous preview. Each run creates a new package directory; `build/desktop-windows/latest-package.txt` contains its path. Keep that directory in place while the development package is registered.

Windows task reminders run while the app remains resident in the notification area; explicit Quit stops them.

Add a task in the app; test notifications; switch languages; close and reopen through the tray. Add the VerveTwoDo widget to Windows Widgets and complete a task with the app resident and after explicitly quitting. The script writes its log to `build/desktop-windows/build.log`.

## Process and widget contracts

The helper accepts one JSON request per line on standard input and writes one response per line to standard output. Standard error is reserved for diagnostics. Requests and responses use protocol version 1 and matching request IDs. Mutations include durable command IDs; replaying an acknowledged command cannot repeat a mutation. Reusing an ID for another command is rejected.

Widgets read `snapshot.json`, which contains a version, revision, locale, and task summaries. They write individual command files atomically into `commands/`. The desktop app acknowledges them only after the helper has durably committed the operation. Invalid commands are retained with a `.rejected` suffix. Credentials and sync keys are excluded from snapshots.

The wire and widget protocols remain version 1. Local storage uses version 4, including the durable operation journal and synchronized per-task rollover; version 1, 2, and 3 stores are readable. Older previews reject the upgraded file, protecting task and synchronization state from being discarded. Command hashes and native-command receipts persist across restarts. Task transactions include a base revision and commit every changed record together. The preview limits a task snapshot to 3 MiB to stay within the bounded process protocol.

On macOS task storage is `~/Library/Application Support/VerveTwoDo/Preview`. The app never opens the old App Group automatically. On the first upgrade, select its existing `Preview` folder in the system folder picker to authorize a transfer. The transfer requires the older app to be quit, copies the full store and synchronization replica atomically, and preserves the original folder. The host publishes widget snapshots to `~/Library/Application Support/VerveTwoDoWidget` and consumes completion commands from its `commands` subdirectory as well as its original inbox. Only widget snapshots and commands go into that directory, never the task database or synchronization credentials. The host never accesses the extension's protected container. Windows uses `%LOCALAPPDATA%/VerveTwoDo/Preview`. `VERVETWODO_PREVIEW_DATA` overrides desktop storage for isolated testing, with a separate `widget` subdirectory on macOS.

Rollover is available in the desktop task editor and bulk editor. Its default applies to newly created tasks and can be overridden per task. While the desktop app is running, it checks once a minute and on startup, moving unfinished enabled tasks from past dates to today while preserving their time, recurrence, and checklist. The per-task choice synchronizes with Android; absent legacy values preserve existing Android choices.
