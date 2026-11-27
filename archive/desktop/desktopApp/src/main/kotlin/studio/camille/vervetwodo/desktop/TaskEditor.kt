package studio.camille.vervetwodo.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import studio.camille.vervetwodo.integration.*
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TaskEditor(task: WidgetTask, original: WidgetTask?, tags: List<TaskTag>, text: Texts, busy: Boolean,
    modifier: Modifier, onChange: (WidgetTask) -> Unit, onCancel: () -> Unit, onSave: (WidgetTask) -> Unit, spacious: Boolean = false) {
    var step by remember(task.id) { mutableStateOf("") }
    var error by remember(task.id) { mutableStateOf<String?>(null) }
    var interval by remember(task.id) { mutableStateOf((task.recurrence?.interval ?: 1).toString()) }
    val focus = remember(task.id) { FocusRequester() }
    LaunchedEffect(task.id) { focus.requestFocus() }
    fun withDate(date: String) = task.copy(dueDate = date, dueTime = if (date.isEmpty()) "" else task.dueTime,
        timeZone = if (date.isEmpty()) "" else task.timeZone, recurrence = if (date.isEmpty()) null else task.recurrence?.let {
            if (date == original?.dueDate) it.copy(anchor = original.recurrence?.anchor ?: date) else it.copy(anchor = date)
        })
    fun pendingSteps() = listOf(step.trim()).filter(String::isNotEmpty).map { ChecklistItem(UUID.randomUUID().toString(), it) }
    fun addStep() { val additions = pendingSteps(); if (additions.isNotEmpty()) { onChange(task.copy(checklist = task.checklist + additions)); step = "" } }
    fun save() {
        if (busy || task.title.isBlank()) return
        val result = task.copy(checklist = task.checklist + pendingSteps(), recurrence = task.recurrence?.copy(interval = interval.toIntOrNull() ?: 0))
        try { TaskRules.validate(result); error = null; onSave(result) }
        catch (e: IllegalArgumentException) { error = e.message?.takeIf { it.startsWith("invalid") } ?: "invalidTask" }
    }
    val writing: @Composable ColumnScope.() -> Unit = {
            OutlinedTextField(task.title, { onChange(task.copy(title = it)) }, Modifier.fillMaxWidth().focusRequester(focus), label = { Text(text("title")) }, minLines = if (spacious) 1 else 2, textStyle = if (spacious) MaterialTheme.typography.headlineSmall else LocalTextStyle.current)
            OutlinedTextField(task.details, { onChange(task.copy(details = it)) }, Modifier.fillMaxWidth(), label = { Text(text("notes")) }, supportingText = { Text(text("markdownHint")) }, minLines = if (spacious) 6 else 3)
            Text(text("checklist"), style = MaterialTheme.typography.titleSmall)
            task.checklist.forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(item.completed, { value -> onChange(task.copy(checklist = task.checklist.map { if (it.id == item.id) it.copy(completed = value) else it })) }, modifier = Modifier.semantics { contentDescription = item.title })
                    OutlinedTextField(item.title, { value -> onChange(task.copy(checklist = task.checklist.map { if (it.id == item.id) it.copy(title = value) else it })) }, Modifier.weight(1f), label = { Text(text("step")) })
                    TextButton(onClick = { onChange(task.copy(checklist = task.checklist.filterNot { it.id == item.id })) }) { Text(text("remove")) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(step, { step = it }, Modifier.weight(1f).testTag("subtask-composer")
                    .onPreviewKeyEvent {
                        if ((it.key == Key.Enter || it.key == Key.NumPadEnter) && !it.isShiftPressed) {
                            if (it.type == KeyEventType.KeyUp && !busy) addStep()
                            true
                        } else false
                    }, enabled = !busy, label = { Text(text("newStep")) }, maxLines = 5,
                    keyboardActions = KeyboardActions(onDone = { addStep() }))
                TextButton(onClick = ::addStep, enabled = step.isNotBlank()) { Text(text("add")) }
            }
    }
    val scheduling: @Composable ColumnScope.() -> Unit = {
            Text(text("dueDate"), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { onChange(withDate(LocalDate.now().toString())) }) { Text(text("today")) }
                TextButton(onClick = { onChange(withDate(LocalDate.now().plusDays(1).toString())) }) { Text(text("tomorrow")) }
            }
            DateInput(task.dueDate, text, !busy) { onChange(withDate(it)) }
            if (task.dueDate.isNotEmpty()) {
                TimeInput(task.dueTime, text, text("includeTime"), enabled = !busy) {
                    onChange(task.copy(dueTime = it, timeZone = if (it.isEmpty()) "" else task.timeZone.ifEmpty { ZoneId.systemDefault().id }))
                }
                if (task.dueTime.isNotEmpty()) TimeZoneInput(task.timeZone, text, !busy) { onChange(task.copy(timeZone = it)) }
            }
            Column {
                if (task.dueDate.isNotEmpty()) {
                    val rule = task.recurrence
                    var expanded by remember(task.id) { mutableStateOf(false) }
                    if (rule == null) {
                        TextButton(onClick = {
                            interval = "1"
                            onChange(task.copy(recurrence = Recurrence("daily", 1, task.dueDate)))
                        }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
                            Icon(painterResource("scheduling/repeat_off.svg"), null, Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(text("recurrence"), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        }
                    } else {
                        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(painterResource("scheduling/repeat.svg"), text("recurrence"), Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(text("repeatEvery"), style = MaterialTheme.typography.bodyLarge)
                            OutlinedTextField(interval, { value ->
                                if (value.length <= 3 && value.all { it in '0'..'9' }) {
                                    interval = value
                                    value.toIntOrNull()?.takeIf { it in 1..999 }?.let { onChange(task.copy(recurrence = rule.copy(interval = it))) }
                                }
                            }, Modifier.width(64.dp).semantics { contentDescription = text("repeatInterval") },
                                enabled = !busy, singleLine = true, isError = interval.toIntOrNull() !in 1..999,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center))
                            Box(Modifier.weight(1f)) {
                                TextButton(onClick = { expanded = true }, enabled = !busy,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = text("repeatUnit") },
                                    contentPadding = PaddingValues(horizontal = 8.dp)) {
                                    Text(text(rule.frequency + if (interval.toIntOrNull() == 1) "Single" else "Plural"), style = MaterialTheme.typography.bodyLarge)
                                    Spacer(Modifier.width(8.dp))
                                    Icon(painterResource("scheduling/expand.svg"), null, Modifier.size(20.dp))
                                }
                                DropdownMenu(expanded, { expanded = false }) {
                                    listOf("daily", "weekly", "monthly", "yearly").forEach { frequency ->
                                        DropdownMenuItem(text = { Text(text(frequency + if (interval.toIntOrNull() == 1) "Single" else "Plural")) }, onClick = {
                                            val anchor = original?.recurrence?.takeIf { it.frequency == frequency && original.dueDate == task.dueDate }?.anchor ?: task.dueDate
                                            onChange(task.copy(recurrence = rule.copy(frequency = frequency, anchor = anchor)))
                                            expanded = false
                                        })
                                    }
                                    HorizontalDivider()
                                    DropdownMenuItem(text = { Text(text("never")) }, onClick = { onChange(task.copy(recurrence = null)); interval = "1"; expanded = false })
                                }
                            }
                        }
                        if (interval.toIntOrNull() !in 1..999) Text(text("repeatIntervalError"),
                            Modifier.padding(start = 40.dp, bottom = 8.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
                val rollover = task.rollover == true
                val color = if (rollover) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                    .toggleable(value = rollover, enabled = !busy, role = Role.Switch, onValueChange = { onChange(task.copy(rollover = it)) })
                    .heightIn(min = 56.dp).padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(painterResource("scheduling/${if (rollover) "rollover" else "rollover_off"}.svg"), null, Modifier.size(20.dp), tint = color)
                    Text(text("rollover"), Modifier.weight(1f), color = color, style = MaterialTheme.typography.bodyLarge)
                    Switch(rollover, onCheckedChange = null, enabled = !busy)
                }
            }
            Text(text("tags"), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                tags.forEach { tag -> FilterChip(tag.id in task.tags, { onChange(task.copy(tags = if (tag.id in task.tags) task.tags - tag.id else task.tags + tag.id)) }, label = { Text(tag.name) }, leadingIcon = { CategoryArt(tag.icon, null, color = tag.color) }) }
            }
            if (tags.isEmpty()) Text(text("createTagsHint"), style = MaterialTheme.typography.bodySmall)
            Text(text("priority"), style = MaterialTheme.typography.titleSmall)
            Choice("priority${task.priority}", (0..4).map { "priority$it" }, text) { onChange(task.copy(priority = it.removePrefix("priority").toInt())) }
    }
    Column(modifier.fillMaxHeight().onPreviewKeyEvent {
        if (spacious && (it.isMetaPressed || it.isCtrlPressed) && (it.key == Key.Enter || it.key == Key.NumPadEnter)) {
            if (it.type == KeyEventType.KeyUp) save()
            true
        } else false
    }) {
        Row(Modifier.fillMaxWidth().padding(horizontal = if (spacious) 28.dp else 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(text(if (original == null) "newTask" else "editTask"), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            if (spacious) TextButton(onClick = onCancel, enabled = !busy) { Text(text("close")) }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            if (spacious && maxWidth >= 840.dp) {
                Row(Modifier.fillMaxSize()) {
                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(28.dp, 12.dp, 28.dp, 24.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp), content = writing)
                    VerticalDivider()
                    Column(Modifier.width(360.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .verticalScroll(rememberScrollState()).padding(24.dp, 12.dp, 24.dp, 24.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp), content = scheduling)
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    writing()
                    if (spacious) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    scheduling()
                }
            }
        }
        if (spacious) HorizontalDivider()
        Column(Modifier.fillMaxWidth().background(if (spacious) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surface)
            .padding(horizontal = if (spacious) 28.dp else 20.dp, vertical = 16.dp)) {
            error?.let { Text(text(it), Modifier.padding(bottom = 8.dp), color = MaterialTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (spacious) Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = onCancel, enabled = !busy) { Text(text("cancel")) }
                Button(onClick = ::save, enabled = !busy && task.title.isNotBlank(),
                    modifier = if (spacious) Modifier.widthIn(min = 100.dp) else Modifier.weight(1f)) { Text(text("save")) }
            }
        }
    }
}

@Composable
internal fun TaskModal(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        BoxWithConstraints {
            Surface(Modifier.width(minOf(1000.dp, maxWidth * .92f)).height(minOf(760.dp, maxHeight * .9f)),
                shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface, content = content)
        }
    }
}

@Composable
internal fun NewTaskDialog(task: WidgetTask, tags: List<TaskTag>, text: Texts, busy: Boolean,
    onChange: (WidgetTask) -> Unit, onCancel: () -> Unit, onSave: (WidgetTask) -> Unit) {
    TaskModal(onDismiss = { if (!busy) onCancel() }) {
        TaskEditor(task, null, tags, text, busy, Modifier, onChange, onCancel, onSave, spacious = true)
    }
}

@Composable
internal fun TagEditor(tag: TaskTag, snapshot: WidgetSnapshot, text: Texts, busy: Boolean, commit: Commit, close: () -> Unit) {
    var name by remember(tag.id) { mutableStateOf(tag.name) }
    var color by remember(tag.id) { mutableStateOf(tag.color) }
    var icon by remember(tag.id) { mutableStateOf(tag.icon) }
    val baseline = remember(tag.id) { snapshot.tags.find { it.id == tag.id } }
    val valid = name.isNotBlank() && name.trim().length <= 100 && color.matches(Regex("#[0-9a-fA-F]{6}")) && snapshot.tags.none { it.id != tag.id && it.name.equals(name.trim(), true) }
    AlertDialog(onDismissRequest = close, title = { Text(text(if (baseline != null) "editTag" else "newTag")) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text(text("tagName")) }, singleLine = true)
            TagColorPicker(color, text) { color = it }
            CategoryPicker(icon, color, text) { icon = it }
            if (!valid) Text(text("invalidTag"), style = MaterialTheme.typography.bodySmall)
        } }, confirmButton = { TextButton(enabled = !busy && valid, onClick = {
            commit({ current ->
                require(current.tags.find { it.id == tag.id } == baseline) { "taskChanged" }
                TaskRules.transaction(current, emptyList(), current.tags.filterNot { it.id == tag.id } + tag.copy(name = name.trim(), color = color, icon = icon))
            }, close)
        }) { Text(text("save")) } }, dismissButton = { TextButton(onClick = close) { Text(text("cancel")) } })
}

@Composable
internal fun TagDeleteDialog(tag: TaskTag, text: Texts, busy: Boolean, commit: Commit, close: () -> Unit) {
    AlertDialog(onDismissRequest = { if (!busy) close() },
        title = { Text(text("deleteTag") + ": " + tag.name) },
        text = { Text(text("deleteTagBody")) },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            commit({ current ->
                require(current.tags.find { it.id == tag.id } == tag) { "taskChanged" }
                val changed = current.tasks.filter { tag.id in it.tags }.map { it.copy(tags = it.tags - tag.id) }
                // Deletion only removes an existing identity; synchronized duplicate names
                // among the untouched tags must not prevent cleanup.
                TaskRules.transaction(current, changed).copy(tags = current.tags.filterNot { it.id == tag.id })
            }, close)
        }) { Text(text("delete"), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(enabled = !busy, onClick = close) { Text(text("cancel")) } })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BulkEditor(ids: Set<String>, snapshot: WidgetSnapshot, text: Texts, busy: Boolean, commit: Commit, close: () -> Unit) {
    var priority by remember { mutableStateOf("unchanged") }
    var due by remember { mutableStateOf("") }
    var changeDate by remember { mutableStateOf(false) }
    var rollover by remember { mutableStateOf("unchanged") }
    var tagMode by remember { mutableStateOf("unchanged") }
    var tags by remember { mutableStateOf(emptySet<String>()) }
    var error by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = close, title = { Text(text("bulkEdit")) }, text = {
        Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text("priority")); Choice(priority, listOf("unchanged") + (0..4).map { "priority$it" }, text) { priority = it }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(changeDate, { changeDate = it }); Text(text("dueDate")) }
            if (changeDate) {
                Row {
                    TextButton(onClick = { due = LocalDate.now().toString() }) { Text(text("today")) }
                    TextButton(onClick = { due = LocalDate.now().plusDays(1).toString() }) { Text(text("tomorrow")) }
                }
                DateInput(due, text, !busy) { due = it }
                Text(text("emptyClearsDate"), style = MaterialTheme.typography.bodySmall)
            }
            Text(text("rollover")); Choice(rollover, listOf("unchanged", "rolloverOn", "rolloverOff"), text) { rollover = it }
            Text(text("tags")); Choice(tagMode, listOf("unchanged", "addTags", "removeTags", "replaceTags"), text) { tagMode = it }
            if (tagMode != "unchanged") FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { snapshot.tags.forEach { tag ->
                FilterChip(tag.id in tags, { tags = if (tag.id in tags) tags - tag.id else tags + tag.id }, label = { Text(tag.name) })
            } }
            if (error) Text(text("invalidDate"), color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = {
        if (changeDate && due.isNotEmpty() && runCatching { LocalDate.parse(due) }.isFailure) { error = true; return@TextButton }
        commit({ current -> TaskRules.transaction(current, current.tasks.filter { it.id in ids && !it.deleted }.map { task ->
            task.copy(priority = if (priority == "unchanged") task.priority else priority.removePrefix("priority").toInt(),
                rollover = if (rollover == "unchanged") task.rollover else rollover == "rolloverOn",
                dueDate = if (changeDate) due else task.dueDate, dueTime = if (changeDate && due.isEmpty()) "" else task.dueTime,
                timeZone = if (changeDate && due.isEmpty()) "" else task.timeZone,
                recurrence = if (!changeDate) task.recurrence else if (due.isEmpty()) null else task.recurrence?.let { if (due == task.dueDate) it else it.copy(anchor = due) },
                tags = when (tagMode) { "addTags" -> (task.tags + tags).distinct(); "removeTags" -> task.tags - tags; "replaceTags" -> tags.toList(); else -> task.tags })
        }) }, close)
    }) { Text(text("apply")) } }, dismissButton = { TextButton(onClick = close) { Text(text("cancel")) } })
}
