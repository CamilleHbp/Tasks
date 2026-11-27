---
name: Tasks
description: Task collections, editing controls and category artwork for Android and the desktop PWA.
colors:
  android-seed: "#0061A4"
  category-backing: "#F4E6C8"
  web-primary: "#245c86"
  web-surface: "#fff"
  web-selected: "#e2edf6"
  web-band: "#f6f8fb"
  web-line: "#e2e5eb"
  web-sidebar: "#f5f6f8"
  web-ink: "#252b36"
typography:
  web-heading:
    fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif'
    fontSize: "30px"
    fontWeight: 650
    letterSpacing: "-0.7px"
  web-task:
    fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif'
    fontSize: "15px"
    lineHeight: 1.4
rounded:
  web-button: "8px"
  web-field: "7px"
  web-dialog: "16px"
spacing:
  android-gutter: "16dp"
  web-control-gap: "8px"
components:
  android-collection:
    width: "840dp"
  android-completion:
    size: "48dp"
  web-button-primary:
    backgroundColor: "{colors.web-primary}"
    textColor: "#fff"
    rounded: "{rounded.web-button}"
    padding: "9px 14px"
  web-button-secondary:
    backgroundColor: "{colors.web-surface}"
    textColor: "{colors.web-ink}"
    rounded: "{rounded.web-button}"
    padding: "9px 14px"
  web-search:
    backgroundColor: "{colors.web-sidebar}"
    rounded: "{rounded.web-field}"
    padding: "10px 12px 10px 38px"
  web-navigation-active:
    backgroundColor: "{colors.web-selected}"
    textColor: "{colors.web-primary}"
    padding: "10px 12px"
  web-tag-chip:
    padding: "6px 10px"
    rounded: "20px"
  web-task-row:
    typography: "{typography.web-task}"
    padding: "7px 12px"
---

# Design System: Tasks

## Overview

**Creative North Star: "Quieter"**

In a full Agenda, the task title is the easiest thing to find. The date heading tells you where you are, the ring tells you which tags belong to the task, and a small line below the title carries the details you need to decide whether to open it.

**Key Characteristics:**

- Flat date groups with a count and a disclosure control.
- Separate targets for opening and completing a task.
- System type, named tags and recognizable category artwork.

## Colors

**Android.** Draw surfaces, text, selection and errors from the active Material 3 color scheme. The owner can choose dynamic color, a seed, light or dark appearance, and contrast. The blue seed in the tokens is the fallback. An Agenda row uses its group tint at 4.5%, an empty-day band at 4%, and an emphasized heading at 9%. A selected row uses `secondaryContainer`.

**Desktop PWA.** Use the semantic properties in [the stylesheet](pwa/src/style.css). In the light theme, blue marks primary actions on a white canvas with pale blue-gray date bands. In the dark theme the canvas becomes slate (#1e242c), the bands lighten slightly (#232c36) and selected items use a stronger blue (#283f53). Overdue metadata and destructive actions use the danger color. System appearance follows the browser’s color-scheme preference.

**The Named Color Rule.** Each assigned tag gets an equal arc in the completion ring. Pair its saved color with a name and, when assigned, an illustration on cream.

## Typography

| Role | Android | Desktop PWA |
| --- | --- | --- |
| Page title | Material `headlineSmall` | System sans, 30 px, weight 650 |
| Date group | `titleSmall` with a `labelMedium` count | 13 px, weight 600 |
| Task title | `bodyLarge`, up to two lines in a collection | 15 px, line height 1.4, up to two lines |
| Metadata | `bodySmall` | Muted 11 px text. At narrow widths, title and metadata step down to 14 and 10 px. |

Keep full titles available in detail. Android uses scalable Material type. PWA navigation counts use tabular figures so the digits align.

## Layout

**Android.** Agenda and Tags share a centered collection column up to 840 dp wide. Navigation adapts from a bottom bar to a rail. Rows start at 56 dp and grow with text. Detail uses up to 720 dp and editing up to 680 dp. The editor keeps Save above the keyboard.

**Desktop PWA.** A 252 px sidebar holds New task, destinations and tag shortcuts. The workspace is capped at 1100 px. At 850 px and below the sidebar narrows to 215 px and the task dialog stacks its writing and scheduling columns. At 600 px and below navigation moves above the workspace. At 1000 px and above the dialog columns scroll independently while its header and footer stay visible.

## Elevation & Depth

Task rows share a continuous band with fine dividers. On Android, sheets, dialogs and the floating add action use Material elevation. In the PWA, the task dialog casts a diffuse shadow over a dimmed, blurred backdrop. Buttons change background on hover and retain a visible keyboard focus outline. The PWA’s reduced-motion setting suppresses transitions.

## Shapes

Use a circle for completion and rounded pills for tags. In Android task rows, category art sits inside a 36 dp circular frame around a 28 dp illustration. The regular PWA row uses a 34 px frame with a 26 px image. Both give the frame the saved tag color. Browser fields and buttons have subtly rounded corners, while the larger dialog uses a 16 px radius.

## Components

| Component | Behavior |
| --- | --- |
| Collection row | The circle toggles completion. The title opens the task. Show up to two tag illustrations in assignment order and a +N overflow count when needed. Strike through the completed title while keeping the metadata legible. In bulk selection, replace completion with a selection checkbox. |
| Android editor and detail | The editor leads with title, notes and checklist, followed by date, tags and priority. Give the five-position priority slider one accessible value. In detail, pair the completion checkbox with its state label and keep Edit near the title. |
| PWA task dialog | Put title, notes and checklist in the writing column and scheduling, priority and tags alongside. An existing task opens with editable values marked by pencils. New or changed drafts show Cancel and Save. Control/Command + Enter submits the draft. |
| Tags | Android’s tag cloud pairs names with counts and gives favorites a star. In the PWA, sidebar shortcuts show names and open-task counts. Chips wrap within the available width. Keep tag names available when the row shows artwork alone. |
| Android collection widget | Keep add, configuration and disclosure in the toolbar. Each task has separate open and completion targets. Group counts and category artwork stay recognizable at launcher size. |

## Do's and Don'ts

### Do:

- **Do** give completion, opening and selection distinct accessible actions.
- **Do** keep overdue words, checked states and tag names readable in both themes.
- **Do** keep Save reachable when the keyboard is open or the dialog content scrolls.

### Don't:

- **Don't** use the ring segments to show checklist progress.
- **Don't** give individual Agenda tasks elevated cards.
- **Don't** carry Android dimensions into browser CSS. Use the PWA’s responsive layout and controls.
