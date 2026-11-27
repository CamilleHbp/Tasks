package studio.camille.vervetwodo.desktop

import com.mikepenz.markdown.m3.Markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import studio.camille.vervetwodo.integration.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.UUID
import java.util.prefs.Preferences

internal fun repeatLabel(rule: Recurrence, text: (String) -> String): String =
    text("repeatSummary").format(rule.interval, text(rule.frequency + if (rule.interval == 1) "Single" else "Plural"))

internal typealias Texts = (String) -> String
internal typealias Commit = ((WidgetSnapshot) -> TaskCommand, () -> Unit) -> Unit

@Composable
internal fun Choice(value: String, values: List<String>, text: Texts, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text(text(value)) }
        DropdownMenu(expanded, { expanded = false }) {
            values.forEach { option -> DropdownMenuItem(text = { Text(text(option)) }, onClick = { onChange(option); expanded = false }) }
        }
    }
}

@Composable
internal fun TagDot(color: String) {
    val rgb = color.removePrefix("#").toLongOrNull(16) ?: 0x3877B8
    Box(Modifier.size(8.dp).background(Color(0xFF000000 or rgb), androidx.compose.foundation.shape.CircleShape))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TaskWorkspace(snapshot: WidgetSnapshot, text: Texts, locale: Locale, busy: Boolean,
    activation: TaskCommand?, newRequest: Int, searchRequest: Int, commit: Commit,
    onSettings: () -> Unit, onEditing: (Boolean) -> Unit, rolloverDefault: Boolean = false, operationMessage: String? = null, syncIndicator: @Composable () -> Unit = {}) {
    val preferences = remember { Preferences.userRoot().node("studio/camille/vervetwodo/preview/views") }
    var destination by remember { mutableStateOf("agenda") }
    var selectedTag by remember { mutableStateOf<String?>(null) }
    var selectedDate by remember { mutableStateOf<String?>(null) }
    var agendaExpanded by remember { mutableStateOf(preferences.getBoolean("sidebarAgendaExpanded", true)) }
    var tagsExpanded by remember { mutableStateOf(preferences.getBoolean("sidebarTagsExpanded", true)) }
    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf(preferences.get("status", "openTasks")) }
    var sort by remember { mutableStateOf(preferences.get("sort", "due")) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var selecting by remember { mutableStateOf(false) }
    var detailID by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf<WidgetTask?>(null) }
    var original by remember { mutableStateOf<WidgetTask?>(null) }
    var discard by remember { mutableStateOf(false) }
    var deleteIDs by remember { mutableStateOf(emptySet<String>()) }
    var tagEditor by remember { mutableStateOf<TaskTag?>(null) }
    var tagToDelete by remember { mutableStateOf<TaskTag?>(null) }
    var bulkEdit by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    var today by remember { mutableStateOf(LocalDate.now()) }
    LaunchedEffect(Unit) { while (true) { today = LocalDate.now(); kotlinx.coroutines.delay(30000) } }
    LaunchedEffect(status, sort) { preferences.put("status", status); preferences.put("sort", sort) }
    LaunchedEffect(draft) { onEditing(draft != null) }
    fun newTask() {
        if (draft != null) return
        original = null
        draft = WidgetTask(UUID.randomUUID().toString(), "", tags = selectedTag?.takeIf { it.isNotEmpty() }?.let(::listOf) ?: emptyList(), rollover = rolloverDefault)
        detailID = null
    }
    LaunchedEffect(newRequest) { if (newRequest > 0) newTask() }
    LaunchedEffect(searchRequest) { if (searchRequest > 0 && draft == null) { detailID = null; withFrameNanos { }; searchFocus.requestFocus() } }
    var handledActivation by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(activation?.id, draft == null) {
        // Preserve an existing draft and open the requested task once it is saved
        // or discarded, rather than silently losing the widget click.
        if (activation != null && activation.id != handledActivation && draft == null) {
            handledActivation = activation.id
            if (activation.action == "add") newTask()
            else if (activation.taskId.isNotEmpty()) detailID = activation.taskId
        }
    }
    val agendaGroups = remember(snapshot, status, sort, today, locale) {
        taskGroups(snapshot, if (status == "openTasks") "open" else status, sort, null, "", today, locale)
    }
    val groups = remember(snapshot, status, sort, selectedTag, selectedDate, query, today, locale) {
        if (selectedDate != null) {
            val group = agendaGroups.find { it.key == selectedDate } ?: TaskGroup(selectedDate!!, emptyList())
            if (query.isBlank()) listOf(group) else taskGroups(snapshot.copy(tasks = group.tasks),
                if (status == "openTasks") "open" else status, sort, null, query, today, locale)
        } else taskGroups(snapshot, if (status == "openTasks") "open" else status, sort, selectedTag, query, today, locale)
    }
    fun navigate(item: String, tag: String? = null, date: String? = null) {
        destination = item; selectedTag = tag; selectedDate = date
        query = ""; selecting = false; selected = emptySet(); detailID = null
        if (tag != null) status = "all"
    }
    val shown = groups.flatMap { it.tasks }.distinctBy { it.id }
    LaunchedEffect(shown.map { it.id }) { selected = selected.intersect(shown.map { it.id }.toSet()) }
    val detail = snapshot.tasks.find { it.id == detailID && !it.deleted }
    fun saveDraft(value: WidgetTask) {
        val baseline = original
        commit({ current ->
            require(baseline == null || current.tasks.find { it.id == baseline.id } == baseline) { "taskChanged" }
            require(value.tags.all { id -> current.tags.any { it.id == id } }) { "taskChanged" }
            TaskRules.transaction(current, listOf(value))
        }, { detailID = value.id; draft = null; original = null })
    }
    val cloud = destination == "tags" && selectedTag == null
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val narrow = maxWidth < 1000.dp
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.width(if (narrow) 208.dp else 224.dp).fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceContainerLow).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("VerveTwoDo", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    syncIndicator()
                }
                WorkspaceSidebar(snapshot, text, locale, today, destination, selectedTag, selectedDate,
                    agendaExpanded, tagsExpanded, Modifier.weight(1f),
                    onAgendaToggle = {
                        agendaExpanded = !agendaExpanded
                        preferences.putBoolean("sidebarAgendaExpanded", agendaExpanded)
                    }, onTagsToggle = {
                        tagsExpanded = !tagsExpanded
                        preferences.putBoolean("sidebarTagsExpanded", tagsExpanded)
                    }, onDate = { navigate("agenda", date = it) }, onTag = { navigate("tags", tag = it) },
                    onViewAll = { navigate("agenda") }, onManageTags = { navigate("tags") })
                Text(text("localOnly"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onSettings) { Text(text("settings")) }
            }
            VerticalDivider()
            Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 24.dp)) {
                Row(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (cloud) text("tags") else if (selectedDate != null) agendaDateLabel(selectedDate!!, today, locale, text) else snapshot.tags.find { it.id == selectedTag }?.name ?: text(if (selectedTag == "") "untagged" else "agenda"),
                        style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    if (cloud) Button(onClick = { tagEditor = TaskTag(UUID.randomUUID().toString(), "") }, enabled = !busy) { Text(text("newTag")) }
                    else Button(onClick = ::newTask, enabled = !busy && draft == null) { Text(text("newTask")) }
                }
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().focusRequester(searchFocus),
                    label = { Text(text(if (cloud) "searchTags" else "search")) }, singleLine = true)
                if (cloud) {
                    LazyColumn(Modifier.weight(1f).padding(top = 16.dp)) {
                        item { TextButton(onClick = { navigate("tags", tag = "") }) { Text(text("untagged")) } }
                        items(snapshot.tags.filter { it.name.contains(query, true) }.sortedBy { it.name.lowercase(locale) }, key = { it.id }) { tag ->
                            Row(Modifier.fillMaxWidth().clickable { navigate("tags", tag = tag.id) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                CategoryArt(tag.icon, null, color = tag.color)
                                Text(tag.name, Modifier.weight(1f).padding(horizontal = 12.dp), style = MaterialTheme.typography.bodyLarge)
                                Text(snapshot.tasks.count { !it.deleted && tag.id in it.tags }.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TagActionButton("edit", text("editTag") + ": " + tag.name, !busy) { tagEditor = tag }
                                TagActionButton("delete", text("deleteTag") + ": " + tag.name, !busy) { tagToDelete = tag }
                            }
                        }
                        if (snapshot.tags.isEmpty()) item { Text(text("noTags"), Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                } else {
                    FlowRow(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Choice(status, listOf("openTasks", "completed", "all"), text) { status = it }
                        Choice(sort, listOf("due", "latest", "newest", "oldest", "priority", "alphabetical"), text) { sort = it }
                        TextButton(onClick = { selecting = !selecting; selected = emptySet() }) { Text(text(if (selecting) "done" else "select")) }
                        if (selectedTag != null) TextButton(onClick = { selectedTag = null; query = "" }) { Text(text("allTags")) }
                    }
                    if (selecting) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = { selected = shown.map { it.id }.toSet() }) { Text(text("selectAll")) }
                        TextButton(onClick = { bulkEdit = true }, enabled = selected.isNotEmpty() && !busy) { Text(text("edit")) }
                        TextButton(onClick = { commit({ TaskRules.completion(it, selected, true) }, { selected = emptySet() }) }, enabled = selected.isNotEmpty() && !busy) { Text(text("complete")) }
                        TextButton(onClick = { deleteIDs = selected }, enabled = selected.isNotEmpty() && !busy) { Text(text("delete")) }
                        Text(selected.size.toString(), Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.labelMedium)
                    }
                    LazyColumn(Modifier.weight(1f)) {
                        groups.forEach { group ->
                            if (group.key != "results") item(key = group.key) {
                                Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(group.date?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)) ?: text(group.key),
                                        style = MaterialTheme.typography.titleSmall, color = if (group.key == "overdue") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(group.tasks.size.toString(), style = MaterialTheme.typography.labelMedium)
                                }
                            }
                            if (group.tasks.isEmpty()) item { Text(text(if (group.key == "today") "todayEmpty" else "noResults"), Modifier.padding(vertical = 14.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            items(group.tasks, key = { it.id }) { task ->
                                TaskRow(task, snapshot.tags, text, locale, selecting, if (selecting) task.id in selected else task.completed,
                                    task.id == detailID || task.id in selected, busy,
                                    onOpen = { if (selecting) selected = if (task.id in selected) selected - task.id else selected + task.id else if (draft == null) detailID = task.id },
                                    onCheck = { checked -> if (selecting) selected = if (checked) selected + task.id else selected - task.id
                                        else commit({ TaskRules.completion(it, setOf(task.id), checked) }, {}) })
                            }
                        }
                        item { Spacer(Modifier.height(24.dp)) }
                    }
                }
            }
        }
    }
    if (draft != null || detail != null) {
        TaskModal(onDismiss = { if (!busy) { if (draft != null) discard = true else detailID = null } }) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                    if (draft != null) TaskEditor(draft!!, original, snapshot.tags, text, busy, Modifier,
                        onChange = { draft = it }, onCancel = { discard = true }, onSave = ::saveDraft, spacious = true)
                    else if (detail != null) TaskDetail(detail, snapshot, text, locale, busy,
                        close = { detailID = null }, edit = { original = detail; draft = detail }, delete = { deleteIDs = setOf(detail.id) })
                }
                operationMessage?.takeUnless { it == "saved" }?.let { message ->
                    Text(text(message), Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(horizontal = 28.dp, vertical = 12.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text(text("discardTitle")) }, text = { Text(text("discardBody")) },
        confirmButton = { TextButton(onClick = { draft = null; original = null; discard = false }) { Text(text("discard")) } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text(text("keepEditing")) } })
    if (deleteIDs.isNotEmpty()) AlertDialog(onDismissRequest = { deleteIDs = emptySet() }, title = { Text(text("deleteTitle")) }, text = { Text(text("deleteBody")) },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            val ids = deleteIDs
            commit({ current -> TaskRules.transaction(current, current.tasks.filter { it.id in ids && !it.deleted }.map { it.copy(deleted = true, retracted = false) }) },
                { deleteIDs = emptySet(); selected = emptySet(); detailID = null })
        }) { Text(text("delete")) } }, dismissButton = { TextButton(onClick = { deleteIDs = emptySet() }) { Text(text("cancel")) } })
    tagEditor?.let { TagEditor(it, snapshot, text, busy, commit) { tagEditor = null } }
    tagToDelete?.let { tag -> TagDeleteDialog(tag, text, busy, commit) { tagToDelete = null } }
    if (bulkEdit) BulkEditor(selected, snapshot, text, busy, commit) { bulkEdit = false }
}

@Composable
private fun TaskRow(task: WidgetTask, tags: List<TaskTag>, text: Texts, locale: Locale, selecting: Boolean,
    checked: Boolean, highlighted: Boolean, busy: Boolean, onOpen: () -> Unit, onCheck: (Boolean) -> Unit) {
    val assigned = task.tags.mapNotNull { id -> tags.find { it.id == id } }
    val metadata = listOfNotNull(dueLabel(task, locale).takeIf { it.isNotEmpty() }, text("priority${task.priority}").takeIf { task.priority > 0 },
        if (task.checklist.isNotEmpty()) "${task.checklist.count { it.completed }}/${task.checklist.size} ${text("checklist")}" else null,
        task.recurrence?.let { repeatLabel(it, text) })
    Row(Modifier.fillMaxWidth().background(if (highlighted) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
        .clickable(onClick = onOpen).semantics {
            contentDescription = (listOf(task.title) + metadata + assigned.map { it.name }).joinToString(", ")
        }.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        if (selecting) Checkbox(checked, onCheck, enabled = !busy, modifier = Modifier.semantics {
            contentDescription = text(if (selecting) "select" else "reopen") + ": " + task.title
        }) else IconToggleButton(checked = checked, onCheckedChange = onCheck, enabled = !busy, modifier = Modifier.semantics {
            contentDescription = text(if (checked) "reopen" else "complete") + ": " + task.title
        }) { CategoryRing(assigned.map { categoryColor(it.color) }, checked = checked) }
        Column(Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(task.title, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis,
                textDecoration = if (task.completed) TextDecoration.LineThrough else null)
            if (metadata.isNotEmpty()) Text(metadata.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (assigned.isNotEmpty()) Row(Modifier.heightIn(min = 48.dp).clickable(onClick = onOpen)
            .padding(horizontal = 4.dp).semantics { contentDescription = assigned.joinToString { it.name } },
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            assigned.take(2).forEach { CategoryArt(it.icon, null, color = it.color) }
            if (assigned.size > 2) Text("+${assigned.size - 2}", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.background(MaterialTheme.colorScheme.secondaryContainer, androidx.compose.foundation.shape.CircleShape)
                    .padding(horizontal = 6.dp, vertical = 2.dp))
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TaskDetail(task: WidgetTask, snapshot: WidgetSnapshot, text: Texts, locale: Locale, busy: Boolean,
    close: () -> Unit, edit: () -> Unit, delete: () -> Unit) {
    val writing: @Composable ColumnScope.() -> Unit = {
        SelectionContainer { Text(task.title, style = MaterialTheme.typography.headlineSmall) }
        if (task.details.isNotBlank()) {
            Text(text("notes"), style = MaterialTheme.typography.titleSmall)
            SelectionContainer { Markdown(task.details, modifier = Modifier.fillMaxWidth()) }
        }
        if (task.checklist.isNotEmpty()) {
            Text(text("checklist"), style = MaterialTheme.typography.titleSmall)
            task.checklist.forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(item.completed, onCheckedChange = null,
                        modifier = Modifier.semantics {
                            contentDescription = item.title
                            stateDescription = text(if (item.completed) "completed" else "openTasks")
                        })
                    SelectionContainer { Text(item.title, Modifier.padding(start = 12.dp),
                        textDecoration = if (item.completed) TextDecoration.LineThrough else null) }
                }
            }
        }
    }
    val metadata: @Composable ColumnScope.() -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(task.completed, onCheckedChange = null)
            Text(text(if (task.completed) "completed" else "openTasks"), Modifier.padding(start = 12.dp))
        }
        Text(text("dueDate"), style = MaterialTheme.typography.titleSmall)
        Text(dueLabel(task, locale).ifEmpty { text("noDate") })
        Text(task.recurrence?.let { repeatLabel(it, text) } ?: text("never"), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (task.rollover == true) Text(text("rollover"), color = MaterialTheme.colorScheme.primary)
        Text(text("tags"), style = MaterialTheme.typography.titleSmall)
        val assigned = snapshot.tags.filter { it.id in task.tags }
        if (assigned.isEmpty()) Text(text("untagged"), color = MaterialTheme.colorScheme.onSurfaceVariant)
        assigned.forEach { tag ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CategoryArt(tag.icon, null, color = tag.color)
                Text(tag.name, Modifier.weight(1f))
            }
        }
        Text(text("priority"), style = MaterialTheme.typography.titleSmall)
        Text(text("priority${task.priority}"))
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text("viewTask"), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = close, enabled = !busy) { Text(text("close")) }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            if (maxWidth >= 840.dp) {
                Row(Modifier.fillMaxSize()) {
                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(28.dp, 12.dp, 28.dp, 24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp), content = writing)
                    VerticalDivider()
                    Column(Modifier.width(360.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .verticalScroll(rememberScrollState()).padding(24.dp, 12.dp, 24.dp, 24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp), content = metadata)
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    writing()
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    metadata()
                }
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 28.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = delete, enabled = !busy) { Text(text("delete"), color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.weight(1f))
            Button(onClick = edit, enabled = !busy, modifier = Modifier.widthIn(min = 100.dp)) { Text(text("edit")) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TagActionButton(action: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
            Icon(painterResource("actions/$action.svg"), label, Modifier.size(20.dp),
                tint = if (!enabled) LocalContentColor.current else if (action == "delete") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        }
    }
}
