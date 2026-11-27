package studio.camille.vervetwodo.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import studio.camille.vervetwodo.integration.*
import java.awt.Desktop
import java.awt.SystemTray
import java.awt.desktop.AppReopenedListener
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.Locale
import java.util.ResourceBundle
import java.util.UUID
import java.util.prefs.Preferences

private class TrayIcon : Painter() {
    override val intrinsicSize = Size(32f, 32f)
    override fun DrawScope.onDraw() {
        drawCircle(Color(0xFF456B4D))
        drawLine(Color.White, androidx.compose.ui.geometry.Offset(size.width * .24f, size.height * .50f), androidx.compose.ui.geometry.Offset(size.width * .44f, size.height * .70f), 3f)
        drawLine(Color.White, androidx.compose.ui.geometry.Offset(size.width * .44f, size.height * .70f), androidx.compose.ui.geometry.Offset(size.width * .77f, size.height * .30f), 3f)
    }
}

fun main(args: Array<String>) {
    // Initialize the desktop event loop before opening native services.
    java.awt.Toolkit.getDefaultToolkit()
    val directory = Platform.directory
    Files.createDirectories(directory)
    val lockChannel = FileChannel.open(directory.resolve("desktop.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    val lock = lockChannel.tryLock()
    if (lock == null) {
        WidgetFiles.enqueue(directory, args.firstNotNullOfOrNull(WidgetFiles::activation)
            ?: TaskCommand(id = UUID.randomUUID().toString(), action = "open"))
        lockChannel.close()
        return
    }
    // Capture URL events before native services and Compose initialize. The durable
    // inbox also handles cold-start URLs that arrive before the task snapshot.
    if (Platform.mac && Desktop.isDesktopSupported()) {
        val desktop = Desktop.getDesktop()
        if (desktop.isSupported(Desktop.Action.APP_OPEN_URI)) desktop.setOpenURIHandler { event ->
            WidgetFiles.activation(event.uri.toString())?.let { command ->
                runCatching { WidgetFiles.enqueue(directory, command) }
                    .onFailure { System.err.println("Could not queue task navigation: ${it.message}") }
            }
        }
    }
    val client = HelperClient(Platform.helper, directory)
    runCatching { Platform.initializeNotifications() }.onFailure { System.err.println("Notification initialization failed: ${it.message}") }
    try {
        application {
            val scope = rememberCoroutineScope()
            val reconciliation = remember { Mutex() }
            val preferences = remember { Preferences.userRoot().node("studio/camille/vervetwodo/preview") }
            var visible by remember { mutableStateOf(true) }
            var snapshot by remember { mutableStateOf(WidgetSnapshot()) }
            var loaded by remember { mutableStateOf(false) }
            var message by remember { mutableStateOf<String?>(null) }
            var busy by remember { mutableStateOf(false) }
            var editing by remember { mutableStateOf(false) }
            var quitting by remember { mutableStateOf(false) }
            var settings by remember { mutableStateOf(false) }
            var syncSettings by remember { mutableStateOf(false) }
            var syncBusy by remember { mutableStateOf(false) }
            var syncStatus by remember { mutableStateOf(SyncReply()) }
            val syncClient = remember {
                val id = preferences.get("syncSourceId", "").ifEmpty { UUID.randomUUID().toString().also { preferences.put("syncSourceId", it) } }
                DesktopSync(client, SyncSource(id, if (Platform.mac) "Mac" else "Desktop", if (Platform.mac) "macos" else "windows"))
            }
            var language by remember { mutableStateOf(preferences.get("language", "system")) }
            var theme by remember { mutableStateOf(preferences.get("theme", "system")) }
            var remindersEnabled by remember { mutableStateOf(preferences.getBoolean("reminders", true)) }
            var rolloverDefault by remember { mutableStateOf(preferences.getBoolean("rolloverDefault", false)) }
            var defaultReminderTime by remember { mutableStateOf(preferences.get("defaultReminderTime", "09:00")) }
            val reminderLock = remember { Mutex() }
            var newRequest by remember { mutableStateOf(0) }
            var searchRequest by remember { mutableStateOf(0) }
            var activation by remember { mutableStateOf(args.firstNotNullOfOrNull(WidgetFiles::activation)) }
            var undo by remember { mutableStateOf<Pair<WidgetSnapshot, TaskCommand>?>(null) }
            var importData by remember { mutableStateOf<String?>(null) }
            val systemLocale = remember { Locale.getDefault() }
            val requestedLocale = if (language == "system") systemLocale else Locale.forLanguageTag(language)
            val locale = if (requestedLocale.language == "fr") Locale.FRENCH else Locale.ENGLISH
            SideEffect { Locale.setDefault(locale) }
            val strings = remember(locale) { ResourceBundle.getBundle("messages", if (locale.language == "fr") Locale.FRENCH else Locale.ENGLISH) }
            val iconStrings = remember(locale) { ResourceBundle.getBundle("camille_icons", if (locale.language == "fr") Locale.FRENCH else Locale.ENGLISH) }
            fun text(key: String) = when {
                iconStrings.containsKey(key) -> iconStrings.getString(key)
                strings.containsKey(key) -> strings.getString(key)
                else -> strings.getString("error")
            }
            fun failure(error: Exception) {
                System.err.println("Desktop operation failed: ${error.javaClass.simpleName}: ${error.message}")
                message = when {
                    error is HelperFailure && error.code == "stale_revision" -> "taskChanged"
                    error is HelperFailure && error.code == "storage_limit" -> "storageLimit"
                    error is IllegalArgumentException && strings.containsKey(error.message.orEmpty()) -> error.message
                    else -> "error"
                }
            }
            suspend fun publish(result: WidgetSnapshot) {
                snapshot = result
                loaded = true
                withContext(Dispatchers.IO) {
                    WidgetFiles.publish(directory, result)
                }
            }
            suspend fun refresh() = reconciliation.withLock {
                val latest = withContext(Dispatchers.IO) { client.request("snapshot") }
                if (latest != snapshot) syncStatus = syncStatus.copy(localChanges = true)
                publish(latest)
            }
            suspend fun synchronize(action: () -> WidgetSnapshot = { syncClient.reconcile() }) {
                syncBusy = true
                try { reconciliation.withLock {
                    val result = withContext(Dispatchers.IO) { action() }
                    if (result != snapshot) publish(result)
                    syncStatus = syncClient.status
                } } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    syncStatus = syncClient.status.copy(error = (error as? HelperFailure)?.code ?: "sync_failed")
                } finally { syncBusy = false }
            }
            DisposableEffect(client) {
                client.onSyncChange = { scope.launch { synchronize { syncClient.reconcile(requestSync = false) } } }
                onDispose { client.onSyncChange = null }
            }
            LaunchedEffect(visible) {
                if (visible) {
                    syncStatus = syncStatus.copy(telemetry = syncStatus.telemetry.copy(checked = false, confirmed = false))
                    synchronize()
                    while (true) {
                        delay(SYNC_CHECK_INTERVAL_MS)
                        // Check durable server receipt without reconnecting the native transport.
                        synchronize { syncClient.reconcile(checkOnly = true) }
                    }
                }
            }
            suspend fun commitNow(builder: (WidgetSnapshot) -> TaskCommand, rememberUndo: Boolean = true) {
                reconciliation.withLock {
                    val before = snapshot
                    val command = builder(before)
                    val result = withContext(Dispatchers.IO) { client.request("command", command) }
                    // Durability is already established. Publishing a widget must not turn a saved edit into a failed edit.
                    syncStatus = syncStatus.copy(localChanges = true)
                    snapshot = result
                    if (rememberUndo) undo = before to command
                    message = "saved"
                    runCatching { publish(result) }.onFailure { message = "widgetRefreshError" }
                }
                synchronize()
            }
            val commit: Commit = { builder, success ->
                if (!busy && loaded) {
                    busy = true
                    scope.launch {
                        try { commitNow(builder); success() } catch (error: Exception) { failure(error) }
                        finally { busy = false }
                    }
                }
            }
            fun undoLast() {
                val entry = undo ?: return
                if (busy || editing) return
                busy = true
                scope.launch {
                    try { commitNow({ TaskRules.undo(entry.first, entry.second, it) }, false); undo = null; message = "undone" }
                    catch (error: Exception) { failure(error) }
                    finally { busy = false }
                }
            }
            fun quit() { if (editing || busy) quitting = true else exitApplication() }
            DisposableEffect(Unit) {
                val desktop = if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null
                val listener = AppReopenedListener { scope.launch { visible = true; syncStatus = syncStatus.copy(telemetry = syncStatus.telemetry.copy(checked = false, confirmed = false)); synchronize() } }
                if (Platform.mac) {
                    desktop?.addAppEventListener(listener)
                    if (desktop?.isSupported(Desktop.Action.APP_QUIT_HANDLER) == true) desktop.setQuitHandler { _, response ->
                        response.cancelQuit(); scope.launch { quit() }
                    }
                }
                onDispose { if (Platform.mac) desktop?.removeAppEventListener(listener) }
            }
            LaunchedEffect(language) {
                preferences.put("language", language)
                try { reconciliation.withLock {
                    publish(withContext(Dispatchers.IO) { client.request("locale", locale = if (locale.language == "fr") "fr" else "en") })
                } } catch (error: Exception) { if (error is CancellationException) throw error; message = "helperError" }
            }
            LaunchedEffect(theme) { preferences.put("theme", theme) }
            LaunchedEffect(rolloverDefault) { preferences.putBoolean("rolloverDefault", rolloverDefault) }
            LaunchedEffect(loaded) {
                if (!loaded) return@LaunchedEffect
                while (isActive) {
                    try {
                        val changed = reconciliation.withLock {
                            val current = withContext(Dispatchers.IO) { client.request("snapshot") }
                            val command = TaskRules.rollover(current, java.time.LocalDate.now())
                            if (command == null) false else {
                                publish(withContext(Dispatchers.IO) { client.request("command", command) })
                                true
                            }
                        }
                        if (changed) synchronize()
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        failure(error)
                    }
                    delay(60_000)
                }
            }
            LaunchedEffect(loaded, snapshot.revision, remindersEnabled, defaultReminderTime, language) {
                preferences.putBoolean("reminders", remindersEnabled)
                val defaultTime = runCatching { java.time.LocalTime.parse(defaultReminderTime) }.getOrNull() ?: return@LaunchedEffect
                preferences.put("defaultReminderTime", defaultReminderTime)
                if ((!Platform.mac && !Platform.windows) || !loaded || System.getenv("VERVETWODO_PREVIEW_DATA") != null) return@LaunchedEffect
                while (isActive) {
                    try {
                        reminderLock.withLock { withContext(Dispatchers.IO) {
                            if (Platform.mac) MacNotifications.reconcile(reminders(snapshot, remindersEnabled, 0, text("reminderBody"), defaultTime = defaultTime))
                            else {
                                val now = java.time.Instant.now()
                                for (reminder in reminders(snapshot, remindersEnabled, 0, text("reminderBody"), now.minusSeconds(60), defaultTime)) {
                                    if (reminder.epochSeconds > now.epochSecond) continue
                                    val key = "reminder-${reminder.taskId}"
                                    if (preferences.getLong(key, -1) == reminder.epochSeconds) continue
                                    Platform.notify(reminder.title, reminder.body)
                                    preferences.putLong(key, reminder.epochSeconds)
                                    preferences.flush()
                                }
                            }
                        } }
                    } catch (error: Exception) { if (error is CancellationException) throw error; message = "notificationError" }
                    delay(if (Platform.mac) 60000 else 1000)
                }
            }
            LaunchedEffect(loaded) {
                if (!loaded) return@LaunchedEffect
                while (isActive) {
                    try {
                        for (path in withContext(Dispatchers.IO) {
                            WidgetFiles.commands(directory)
                        }) {
                            val command = try { withContext(Dispatchers.IO) { WidgetFiles.readCommand(path) } }
                            catch (_: Exception) { withContext(Dispatchers.IO) { Files.move(path, path.resolveSibling(path.fileName.toString() + ".rejected")) }; continue }
                            try {
                                if (command.action == "complete") {
                                    commitNow({ current ->
                                        require(current.tasks.any { it.id == command.taskId && !it.deleted }) { "task_not_found" }
                                        // Persist a native receipt with the atomic task transaction for crash-safe widget replay.
                                        TaskRules.completion(current, setOf(command.taskId), command.completed).copy(sourceId = command.id, taskId = command.taskId, completed = command.completed)
                                    }, false)
                                } else { activation = command; settings = false; syncSettings = false; visible = true }
                            } catch (error: Exception) {
                                if (error !is IllegalArgumentException && !(error is HelperFailure && error.code in setOf("task_not_found", "invalid_command", "command_id_reused", "unknown_action"))) throw error
                                withContext(Dispatchers.IO) { Files.move(path, path.resolveSibling(path.fileName.toString() + ".rejected")) }; continue
                            }
                            withContext(Dispatchers.IO) { Files.deleteIfExists(path) }
                        }
                    } catch (error: Exception) { failure(error) }
                    delay(1000)
                }
            }
            if (SystemTray.isSupported()) Tray(icon = remember { TrayIcon() }, tooltip = text("appName"), onAction = { visible = true }, menu = {
                Item(text("open"), onClick = { visible = true }); Separator(); Item(text("quit"), onClick = ::quit)
            })
            Window(onCloseRequest = { if (SystemTray.isSupported()) visible = false else quit() }, visible = visible,
                title = text("appName"), state = rememberWindowState(width = 1180.dp, height = 800.dp)) {
                LaunchedEffect(activation?.id, visible) {
                    if (visible && activation != null) {
                        window.extendedState = java.awt.Frame.NORMAL
                        window.toFront()
                        window.requestFocus()
                    }
                }
                SideEffect { window.minimumSize = java.awt.Dimension(720, 540) }
                DisposableEffect(window) {
                    val listener = object : java.awt.event.WindowFocusListener {
                        override fun windowGainedFocus(event: java.awt.event.WindowEvent) {
                            syncStatus = syncStatus.copy(telemetry = syncStatus.telemetry.copy(checked = false, confirmed = false))
                            scope.launch { synchronize() }
                        }
                        override fun windowLostFocus(event: java.awt.event.WindowEvent) {}
                    }
                    window.addWindowFocusListener(listener)
                    onDispose { window.removeWindowFocusListener(listener) }
                }
                fun chooseFile(save: Boolean, csv: Boolean = false) {
                    val dialog = java.awt.FileDialog(window, text(if (save) if (csv) "exportCsv" else "backup" else "importBackup"),
                        if (save) java.awt.FileDialog.SAVE else java.awt.FileDialog.LOAD)
                    if (save) dialog.file = if (csv) "VerveTwoDo.csv" else "VerveTwoDo-backup.json"
                    dialog.isVisible = true
                    val file = dialog.file?.let { java.nio.file.Path.of(dialog.directory, it) }
                    dialog.dispose()
                    if (file != null) scope.launch {
                        try {
                            if (save) {
                                val data = if (csv) Backups.csv(snapshot) else Backups.encode(snapshot)
                                withContext(Dispatchers.IO) {
                                    val temp = Files.createTempFile(file.parent, ".vervetwodo-", ".tmp")
                                    try { Files.writeString(temp, data); Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
                                    finally { Files.deleteIfExists(temp) }
                                }
                                message = "exported"
                            } else {
                                importData = withContext(Dispatchers.IO) { require(Files.size(file) <= 3 * 1024 * 1024) { "invalidBackup" }; Files.readString(file) }
                                Backups.import(snapshot, importData!!)
                            }
                        } catch (error: Exception) { importData = null; message = if (save) "exportError" else "invalidBackup" }
                    }
                }
                MenuBar {
                    Menu(text("file")) {
                        Item(text("newTask"), enabled = loaded && !editing, shortcut = KeyShortcut(androidx.compose.ui.input.key.Key.N, meta = Platform.mac, ctrl = !Platform.mac), onClick = { visible = true; newRequest++ })
                        Item(text("backup"), enabled = loaded, onClick = { chooseFile(true) })
                        Item(text("exportCsv"), enabled = loaded, onClick = { chooseFile(true, true) })
                        Item(text("importBackup"), enabled = loaded && !busy, onClick = { chooseFile(false) })
                        Separator(); Item(text("quit"), onClick = ::quit)
                    }
                    Menu(text("tasksMenu")) {
                        Item(text("search"), enabled = !editing, shortcut = KeyShortcut(androidx.compose.ui.input.key.Key.F, meta = Platform.mac, ctrl = !Platform.mac), onClick = { searchRequest++ })
                        Item(text("undo"), enabled = undo != null && !busy && !editing, onClick = ::undoLast)
                    }
                    Menu(text("settings")) { Item(text("settings"), onClick = { settings = true }) }
                }
                val dark = theme == "dark" || theme == "system" && isSystemInDarkTheme()
                CompositionLocalProvider(LocalDefaultReminderTime provides defaultReminderTime) {
                MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = Color(0xFFA1C9FF)) else lightColorScheme(primary = Color(0xFF0061A4))) {
                    Surface(Modifier.fillMaxSize()) {
                        Column {
                            SyncAttention(syncStatus, syncBusy, ::text,
                                { scope.launch { synchronize() } }, { syncSettings = true },
                                { record, field, value -> scope.launch { synchronize { syncClient.resolve(record, field, value) } } }, showConflicts = false)
                            Box(Modifier.weight(1f)) {
                                if (loaded) TaskWorkspace(snapshot, ::text, locale, busy, activation, newRequest, searchRequest, commit, { settings = true }, { editing = it }, rolloverDefault, operationMessage = message,
                                    syncIndicator = { SyncStatusButton(syncStatus, ::text,
                                        { scope.launch { synchronize() } }, { syncSettings = true }) })
                                else Column(Modifier.padding(28.dp)) { Text(text("loading")); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                            }
                            message?.let { key ->
                                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(text(key), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                    if (undo != null) TextButton(onClick = ::undoLast, enabled = !busy && !editing) { Text(text("undo")) }
                                    if (key in setOf("error", "helperError", "widgetRefreshError")) TextButton(onClick = { scope.launch { try { refresh(); message = null } catch (error: Exception) { failure(error) } } }) { Text(text("retry")) }
                                    TextButton(onClick = { message = null }) { Text(text("dismiss")) }
                                }
                            }
                        }
                    }
                    if (settings && syncStatus.state.conflicts().isEmpty()) AlertDialog(onDismissRequest = { settings = false }, title = { Text(text("settings")) }, text = {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(text("language")); Choice(language, listOf("system", "en", "fr"), ::text) { language = it }
                            Text(text("appearance")); Choice(theme, listOf("system", "light", "dark"), ::text) { theme = it }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(rolloverDefault, { rolloverDefault = it }, modifier = Modifier.semantics { contentDescription = text("rolloverDefault") })
                                Text(text("rolloverDefault"), Modifier.padding(start = 12.dp))
                            }
                            Text(text("rolloverDefaultHint"), style = MaterialTheme.typography.bodySmall)
                            if (Platform.mac || Platform.windows) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Switch(remindersEnabled, { remindersEnabled = it }, modifier = Modifier.semantics { contentDescription = text("taskReminders") })
                                    Text(text("taskReminders"), Modifier.padding(start = 12.dp))
                                }
                                Text(text("reminderHint"), style = MaterialTheme.typography.bodySmall)
                                if (Platform.windows) Text(text("reminderWindowsHint"), style = MaterialTheme.typography.bodySmall)
                                if (remindersEnabled) TimeInput(defaultReminderTime, ::text, text("defaultReminderTime"), optional = false, fallback = "09:00") { defaultReminderTime = it }
                            }
                            OutlinedButton(onClick = { settings = false; syncSettings = true }) { Text(text("syncTitle")) }
                            OutlinedButton(onClick = { scope.launch {
                                try { withContext(Dispatchers.IO) { Platform.notify(text("notificationTitle"), text("notificationBody")) }; message = "notificationSent" }
                                catch (_: Exception) { message = "notificationError" }
                            } }) { Text(text("notification")) }
                        }
                    }, confirmButton = { TextButton(onClick = { settings = false }) { Text(text("done")) } })
                    if (syncSettings) SyncSettings(syncStatus, syncBusy, ::text, { syncSettings = false },
                        { origin, code -> scope.launch { synchronize { syncClient.connect(origin, code) } } },
                        { scope.launch { synchronize() } })
                    if (quitting) AlertDialog(onDismissRequest = { quitting = false }, title = { Text(text("quitTitle")) }, text = { Text(text("quitBody")) },
                        confirmButton = { TextButton(onClick = ::exitApplication, enabled = !busy) { Text(text("quit")) } },
                        dismissButton = { TextButton(onClick = { quitting = false }) { Text(text("keepEditing")) } })
                    if (importData != null) AlertDialog(onDismissRequest = { importData = null }, title = { Text(text("importBackup")) }, text = { Text(text("importBody")) },
                        confirmButton = { TextButton(enabled = !busy, onClick = { val data = importData!!; commit({ Backups.import(it, data) }, { importData = null }) }) { Text(text("importAction")) } },
                        dismissButton = { TextButton(onClick = { importData = null }) { Text(text("cancel")) } })
                    // Compose after task/editor dialogs so a conflict cannot be hidden behind them.
                    SyncAttention(syncStatus, syncBusy, ::text,
                        { scope.launch { synchronize() } }, { syncSettings = true },
                        { record, field, value -> scope.launch { synchronize { syncClient.resolve(record, field, value) } } },
                        showBanner = false, showConflicts = !syncSettings && !quitting)
                }
                }
            }
        }
    } finally { client.close(); lock.release(); lockChannel.close() }
}
