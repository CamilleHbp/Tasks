# Tasks for the web

Open [Tasks](https://tasks.camille.studio) to plan your day in a browser. To give Tasks its own window, choose **Install app**, **Add to Dock** or **Add to Home Screen** in your browser. After the first load you can work offline.

## Plan a day

Give a task a due date to see it in **Agenda**. Open the task to write Markdown notes, set its priority or break it into checklist steps. For a repeating task, choose a daily, weekly, monthly or yearly schedule. Turn on carry-forward for unfinished work you want to see the following day.

Use **My collection** to keep your own date range, tag filters and sort order. **Favorites** is a shortcut to individual tasks. Deleting a tag keeps the tasks that used it.

<a href="../docs/screenshots/checklist.png"><img src="../docs/screenshots/checklist.png" width="600" alt="Desktop task editor with the garden checklist open"></a>

*Each checklist step has its own checkbox.*

## Sync, reminders and widgets

Open **Settings → Synchronization**. Use `https://tasks.camille.studio` and the same workspace code on your phone and computer. Offline changes sync when you reconnect. If two devices change the same field, choose which value to keep. Disconnecting revokes this browser’s session and keeps its local tasks.

For a reminder while Tasks is closed, connect the workspace, enable push reminders and allow browser notifications. On iOS or iPadOS, install Tasks on the Home Screen first. With local reminders only, keep Tasks open.

On macOS or Windows 11, open **Settings → Desktop widget** to install the companion, then connect it to your workspace. Select a task in the system widget to open it in Tasks.

## Backups and earlier installs

Export JSON for a backup or CSV for a spreadsheet. Importing JSON adds tasks with fresh IDs. Android ZIP backups belong in the Android app. If you used an earlier web address, sync or export local tasks there first. Each web address has its own storage and notification permission.

[Android app and project credits](../README.md) · [Archived desktop app](../archive/desktop/README.md)

## Development

Development requires Node.js 22.12 or newer, Go 1.27, and the pinned [sync-core](https://github.com/CamilleHbp/sync-core) module.

```sh
cd pwa
npm ci
npm run build
npm test
(cd server && GOWORK=off go test -race ./...)
npm run test:e2e
python3 scripts/test-sync.py
```

The browser tests start the local Go gateway and use isolated browser storage. The synchronization test creates temporary upstream and gateway instances and never reads or writes production tasks.

Run the production build locally:

```sh
cd pwa/server
GOWORK=off go run .
```

Open `http://127.0.0.1:8791`. For Vite development, run the gateway with `PUBLIC_ORIGIN=http://127.0.0.1:5173`, then run `npm run dev` in `pwa`. All browser API requests remain on the page’s own origin.

The gateway accepts `PUBLIC_ORIGIN`, `SYNC_ORIGIN`, `LISTEN_ADDR`, `DATA_DIR`, and `ASSET_DIR`. The upstream workspace URL is configured by the operator, not supplied by the browser. Authentication uses expiring, HttpOnly, SameSite cookies. Static caching excludes all API responses. Session hashes, sync credentials, VAPID keys and push subscriptions belong only in the private data directory.

## Deployment

```sh
python3 pwa/scripts/deploy.py
```

The deployment script builds and tests the PWA and gateway, creates an immutable staged release, verifies transferred hashes, and starts only the `vervetwodo-web` Compose project. It adds and validates a dedicated Caddy route when needed. Existing sync and native app services are preserved. Public JavaScript, styles, icons, manifest and service worker are checked by size and SHA-256 after deployment.

The service data is `/srv/homeserver/data/vervetwodo-web`; include it in server backups. Code releases live under `/srv/homeserver/builds/vervetwodo-web`, with `current` pointing at the active release. To roll back, run Docker Compose with project `vervetwodo-web` from a previous release directory, retaining the same data volume. The service worker offers updates for explicit reload after drafts are saved.

For self-hosted deployments, set `SYNC_ORIGIN` to the default native server and `SYNC_ORIGINS` to a comma-separated list of additional HTTPS origins. Each server gets its own replica, sessions and reminder subscriptions. For a multi-workspace gateway, set `SYNC_ROUTE_FILE` to a private JSON file mapping those configured origins to their distinct SHA-256 access-code verifiers. The hosted deployment reads the verifiers from each workspace database. Refresh the routing file and restart the gateway when rotating a workspace code.

## Credits and license

Tasks builds on VerveDo by super12138. Category artwork and English/French application vocabulary are reused from this repository’s desktop resources. The application is distributed under the repository’s GPL-3.0 license. Third-party libraries retain their respective licenses in the dependency metadata.
