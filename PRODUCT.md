# Product

<!-- impeccable:product-schema 1 -->

## Platform

adaptive

## Users

Tasks is for one person managing both personal and work tasks. They might capture something from an Android home-screen widget, work through the checklist at their desk, then check what is due from their phone.

## Product Purpose

Help the owner answer two questions quickly: “What needs my attention?” and “What do I need to do next on this task?” Agenda answers the first by date. Tags bring together work that belongs in the same context. Opening a task reveals its notes and checklist. Completing it is a separate action available directly in the list.

## Operating Context

| | Android | Desktop PWA |
| --- | --- | --- |
| Daily workspace | Native Kotlin and Jetpack Compose app. Agenda and Tags are the main destinations. | Installable web app in `pwa/`. Agenda, Favorites, My collection and Tags are available from navigation. |
| Away from the app | All Incomplete, Today’s Task and Upcoming tasks launcher widgets. Each Upcoming widget has its own collection settings. | Optional macOS and Windows 11 widget companions connect to the private workspace and open tasks in the PWA. |
| Local data | Tasks are stored on the device. Restoring a compatible VerveDo ZIP replaces local app data. | After its first successful load the app works offline. Tasks live in this browser’s IndexedDB. Importing a desktop JSON backup adds tasks with new identities. |

## Capabilities and Constraints

- **Capture and review.** A title is enough to create a task. Add Markdown notes, ordered checklist items, tags, priority, a due date and an optional time. Checklist items belong to their parent and can be completed independently. Deleting a tag keeps its tasks.
- **Find and act.** Search, status filters, sorting and bulk actions work within collections. Agenda keeps unfinished overdue and undated work reachable. Desktop My collection lets the owner save a date scope, tag matching, grouping and sort order.
- **Schedule.** Recurrence creates the next occurrence from its scheduled date. Monthly and yearly series retain their calendar anchor across short months. Carry-forward is a separate per-task choice that moves unfinished past-due work to today. The default applies to new tasks.
- **Synchronize.** The owner can connect Android and the PWA to the same private workspace with an access code. Independent field edits merge. When two devices give a field competing values, the owner can choose which to keep. Local edits survive a failed sync. The task’s carry-forward choice travels with it.
- **Remind.** Android supports task notifications. The PWA can remind while open. Background web reminders require synchronization, notification permission and a browser with push support.

## Accessibility & Inclusion

Follow the device or browser language by default, with English fallback and persistent English and Français choices. Preserve Android’s additional translations. Localize dates, plural counts, widget text and accessible actions. Give category artwork a readable name and completion a distinct checked state. Support Android font scaling and keyboard operation in the PWA.

## Identity and Attribution

Tasks is a GPL-3.0-only fork of VerveDo by Super12138 and contributors. Its Android application ID, `studio.camille.vervetwodo`, allows installation alongside the upstream app. Preserve the credits in [README_EN.md](README_EN.md), [README.md](README.md), [LICENSE](LICENSE) and [the Android licence notices](app/licences/).
