package cn.super12138.todo.ui.pages.collections

import cn.super12138.todo.ui.components.CategoryCompletionRing
import cn.super12138.todo.ui.components.CategoryIconStrip
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.super12138.todo.R
import cn.super12138.todo.logic.assignTagColors
import cn.super12138.todo.logic.database.TaskEntity
import cn.super12138.todo.logic.database.taskTags
import cn.super12138.todo.logic.model.*
import cn.super12138.todo.ui.widget.upcoming.labelRes
import cn.super12138.todo.utils.VibrationUtils
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskCollectionPage(
    mode: TaskViewMode,
    toAdd: () -> Unit,
    toDetail: (TaskEntity) -> Unit,
    toEdit: (TaskEntity) -> Unit,
    toSettings: () -> Unit,
    initialConfiguration: String? = null,
    onBack: (() -> Unit)? = null,
    tagScope: String? = null,
    toAdjacentTag: ((String) -> Unit)? = null,
    viewModel: TaskCollectionViewModel = koinViewModel { parametersOf(mode, initialConfiguration) }
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val config = if (tagScope != null) state.config.copy(mode = TaskViewMode.Tags,
        tags = setOf(tagScope), collapsed = emptySet()) else state.config
    val context = LocalContext.current
    val resources = LocalResources.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(listOf<Int>()) }
    var menu by remember { mutableStateOf(false) }
    var options by rememberSaveable { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    val settings: cn.super12138.todo.logic.SettingsRepository = org.koin.compose.koinInject()
    val scheduling by settings.schedulingPreferencesFlow.collectAsStateWithLifecycle(cn.super12138.todo.logic.SchedulingPreferences())
    val defaultTime by settings.defaultDueTimeFlow.collectAsStateWithLifecycle(cn.super12138.todo.logic.DEFAULT_DUE_TIME_MINUTES)
    val tags by viewModel.catalog.collectAsStateWithLifecycle(emptyList())

    val localeConfiguration = LocalConfiguration.current
    val groups = remember(state, config, query, localeConfiguration, scheduling) {
        if (state.loaded) taskGroups(state.tasks, config, state.today, query = query, firstDayOfWeek = scheduling.weekStart(localeConfiguration.locales[0])) else emptyList()
    }
    val orderedTags = tags + ""
    val tagIndex = orderedTags.indexOf(tagScope)
    val previousTag = orderedTags.getOrNull(tagIndex - 1)
    val nextTag = if (tagIndex >= 0) orderedTags.getOrNull(tagIndex + 1) else null
    val direction = LocalLayoutDirection.current
    val density = LocalDensity.current
    val swipeThreshold = with(density) { 72.dp.toPx() }
    val edgeWidth = with(density) { 24.dp.toPx() }
    val tagSwipe = if (tagScope != null && toAdjacentTag != null && !selecting && !searching) Modifier.pointerInput(tagScope, orderedTags, direction) {
        var distance = 0f
        var fromContent = false
        detectHorizontalDragGestures(onDragStart = {
            distance = 0f
            fromContent = it.x > edgeWidth && it.x < size.width - edgeWidth
        }, onHorizontalDrag = { _, amount -> if (fromContent) distance += amount },
            onDragCancel = { distance = 0f }, onDragEnd = {
                val forward = if (direction == LayoutDirection.Rtl) distance > swipeThreshold else distance < -swipeThreshold
                val backward = if (direction == LayoutDirection.Rtl) distance < -swipeThreshold else distance > swipeThreshold
                if (fromContent) (if (forward) nextTag else if (backward) previousTag else null)?.let(toAdjacentTag)
            })
    } else Modifier
    val listState = rememberLazyListState()
    LaunchedEffect(state.tasks, state.loaded) { if (state.loaded) selected = selected.filter { id -> state.tasks.any { it.id == id } } }

    val title = stringResource(if (mode == TaskViewMode.Agenda) R.string.view_agenda else R.string.view_tags)
    val scopedTitle = tagScope?.ifEmpty { stringResource(R.string.widget_no_tag) }
    val scopedColor = tagScope?.takeIf { it.isNotEmpty() }?.let {
        Color(assignTagColors(listOf(it), state.colors).getValue(it)).copy(alpha = 1f)
    } ?: MaterialTheme.colorScheme.secondaryContainer
    val titleHeight = with(LocalDensity.current) { (MaterialTheme.typography.titleLarge.lineHeight * 2).toDp() } + 24.dp
    val error = stringResource(R.string.view_error)
    LaunchedEffect(viewModel) { viewModel.errors.collect { snackbar.showSnackbar(error) } }
    BackHandler(selecting || searching) {
        if (selecting) { selecting = false; selected = emptyList() }
        else { searching = false; query = "" }
    }
    fun select(task: TaskEntity) {
        selected = if (task.id in selected) selected - task.id else selected + task.id
    }
    fun complete(task: TaskEntity) {
        VibrationUtils.performHapticFeedback(view)
        scope.launch {
            val completed = !task.isCompleted
            if (viewModel.setCompleted(task.id, completed)) {
                snackbar.currentSnackbarData?.dismiss()
                val result = snackbar.showSnackbar(
                    resources.getString(if (completed) R.string.view_task_completed else R.string.view_task_reopened),
                    resources.getString(R.string.widget_undo), withDismissAction = true)
                if (result == SnackbarResult.ActionPerformed) viewModel.setCompleted(task.id, !completed)
            }
        }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {
                    if (!selecting && scopedTitle != null) {
                        Surface(color = scopedColor, shape = RoundedCornerShape(24.dp),
                            modifier = Modifier.semantics { heading() }) {
                            Text(scopedTitle, style = MaterialTheme.typography.titleLarge,
                                color = if (scopedColor.luminance() > 0.179f) Color.Black else Color.White,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        }
                    } else Text(if (selecting) stringResource(R.string.title_selected_count, selected.size) else title,
                        style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                expandedHeight = if (tagScope != null && !selecting) maxOf(64.dp, titleHeight) else 64.dp,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                navigationIcon = {
                    if (selecting || onBack != null) IconButton(onClick = {
                        if (selecting) { selecting = false; selected = emptyList() } else onBack?.invoke()
                    }) { Icon(painterResource(if (selecting) R.drawable.ic_close else R.drawable.ic_arrow_back),
                        stringResource(if (selecting) R.string.action_close else R.string.action_back)) }
                },
                actions = {
                    if (selecting) {
                        IconButton(onClick = { selected = groups.flatMap { it.tasks }.map { it.id }.distinct() }) {
                            Icon(painterResource(R.drawable.ic_select_all), stringResource(R.string.tip_select_all))
                        }
                        IconButton(onClick = { editing = true }, enabled = selected.isNotEmpty()) {
                            Icon(painterResource(R.drawable.ic_edit_task), stringResource(R.string.bulk_edit))
                        }
                        IconButton(onClick = { confirmingDelete = true }, enabled = selected.isNotEmpty()) {
                            Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.action_delete))
                        }
                    } else {
                        IconButton(onClick = { selecting = true }) {
                            Icon(painterResource(R.drawable.ic_select_all), stringResource(R.string.view_select))
                        }
                        IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                            Icon(painterResource(R.drawable.ic_search), stringResource(R.string.action_search))
                        }
                        cn.super12138.todo.ui.components.AppSyncStatusButton()
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(painterResource(R.drawable.ic_more), stringResource(R.string.view_more)) }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                if (tagScope == null) DropdownMenuItem(text = { Text(stringResource(R.string.view_options)) }, onClick = { menu = false; options = true })
                                DropdownMenuItem(text = { Text(stringResource(R.string.page_settings)) }, onClick = { menu = false; toSettings() })
                            }
                        }
                    }
                })
        },
        floatingActionButton = {
            if (!selecting) FloatingActionButton(onClick = toAdd, shape = MaterialTheme.shapes.large,
                containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                Icon(painterResource(R.drawable.ic_add), stringResource(R.string.action_add_task))
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 840.dp).fillMaxSize()) {
                if (searching) OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    label = { Text(stringResource(R.string.action_search)) },
                    trailingIcon = { IconButton(onClick = { query = ""; searching = false }) {
                        Icon(painterResource(R.drawable.ic_close), stringResource(R.string.action_close))
                    } }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
                if (!selecting && tagScope != null) {
                    if (toAdjacentTag != null) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { previousTag?.let(toAdjacentTag) }, enabled = previousTag != null) {
                            Icon(painterResource(R.drawable.ic_chevron_right), stringResource(R.string.tag_previous), Modifier.scale(scaleX = -1f, scaleY = 1f))
                        }
                        Text(stringResource(R.string.tag_swipe_hint), Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        IconButton(onClick = { nextTag?.let(toAdjacentTag) }, enabled = nextTag != null) {
                            Icon(painterResource(R.drawable.ic_chevron_right), stringResource(R.string.tag_next))
                        }
                    }
                    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        itemVerticalAlignment = Alignment.CenterVertically) {
                        ChoiceMenu(stringResource(config.status.labelRes()), TaskStatusFilter.entries.map { it to stringResource(it.labelRes()) }) { status ->
                            viewModel.configure { it.copy(status = status) }
                        }
                        ChoiceMenu(stringResource(config.sort.shortLabelRes()), TaskGroupSort.entries.map { it to stringResource(it.labelRes()) }) { sort ->
                            viewModel.configure { it.copy(sort = sort) }
                        }
                        if (state.loaded) {
                            val count = groups.sumOf { it.tasks.size }
                            Text(androidx.compose.ui.res.pluralStringResource(R.plurals.tag_task_count, count, count),
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
                        }
                    }
                } else if (!selecting) {
                    val tagSummary = when (state.config.tags.size) {
                        0 -> stringResource(R.string.widget_all_tags)
                        1 -> state.config.tags.first().ifEmpty { stringResource(R.string.widget_no_tag) }
                        else -> resources.getQuantityString(R.plurals.widget_tag_count, state.config.tags.size, state.config.tags.size)
                    }
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .combinedClickable(onClick = { viewModel.configure { it.copy(controlsVisible = !it.controlsVisible) } })
                        .padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.view_scope, stringResource(state.config.status.labelRes()), tagSummary,
                            stringResource(state.config.sort.shortLabelRes())), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        IconButton(onClick = { viewModel.configure { it.copy(controlsVisible = !it.controlsVisible) } }) {
                            Icon(painterResource(if (state.config.controlsVisible) R.drawable.ic_widget_collapse else R.drawable.ic_widget_expand),
                                stringResource(if (state.config.controlsVisible) R.string.widget_hide_controls else R.string.widget_show_controls))
                        }
                    }
                    if (state.config.controlsVisible) FlowRow(Modifier.padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChoiceMenu(stringResource(state.config.status.labelRes()), TaskStatusFilter.entries.map { it to stringResource(it.labelRes()) }) { status ->
                            viewModel.configure { it.copy(status = status) }
                        }
                        ChoiceMenu(stringResource(state.config.sort.shortLabelRes()), TaskGroupSort.entries.map { it to stringResource(it.labelRes()) }) { sort ->
                            viewModel.configure { it.copy(sort = sort) }
                        }
                        OutlinedButton(onClick = { options = true }) {
                            Icon(painterResource(R.drawable.ic_tune), null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.view_options))
                        }
                    }
                }
                if (!state.loaded) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(state = listState, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                    modifier = Modifier.fillMaxSize().then(tagSwipe)) {
                    if (state.loaded && (groups.isEmpty() || groups.all { it.tasks.isEmpty() } && query.isNotBlank())) item {
                        Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            val unfilteredTag = tagScope != null && query.isBlank() && config.status == TaskStatusFilter.All
                            Text(stringResource(if (unfilteredTag) R.string.tag_tasks_empty else R.string.view_empty),
                                style = MaterialTheme.typography.titleMedium)
                            if (!unfilteredTag) TextButton(onClick = {
                                query = ""
                                viewModel.configure { it.copy(
                                    status = if (tagScope != null) TaskStatusFilter.All else TaskStatusFilter.Open,
                                    tags = tagScope?.let(::setOf) ?: emptySet()) }
                            }) { Text(stringResource(R.string.view_clear_filters)) }
                        }
                    }
                    groups.forEach { group ->
                        val collapsed = tagScope == null && query.isBlank() && group.key in config.collapsed
                        if (tagScope == null) item(key = "header:" + group.key) {
                            CollectionHeader(group, state.today, state.colors, collapsed, state.config.emphasizeGroups) {
                                viewModel.configure { it.copy(collapsed = if (group.key in it.collapsed) it.collapsed - group.key else it.collapsed + group.key) }
                            }
                        }
                        if (!collapsed) {
                            if (group.tasks.isEmpty()) item(key = "empty:" + group.key) {
                                Text(stringResource(if (group.date == state.today) R.string.view_today_empty else R.string.view_day_empty),
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.fillMaxWidth().background(groupColor(group, state.colors).copy(alpha = 0.04f))
                                        .padding(start = 20.dp, top = 16.dp, bottom = 16.dp))
                            }
                            items(group.tasks, key = { group.key + ":" + it.id }) { task ->
                                CollectionTaskRow(task, group, state.today, state.colors, selecting, task.id in selected,
                                    onClick = { if (selecting) select(task) else toDetail(task) },
                                    onLongClick = { if (selecting) select(task) else toEdit(task) },
                                    onComplete = { complete(task) })
                            }
                        }
                    }
                }
            }
        }
    }
    if (editing) BulkTaskEditor(state.tasks.filter { it.id in selected }, tags, state.colors, defaultTime, scheduling = scheduling,
        onDismiss = { editing = false }, onApply = { viewModel.edit(selected.toSet(), it) })
    if (options) ModalBottomSheet(onDismissRequest = { options = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.view_options), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { options = false }) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.action_close)) }
            }
            TaskViewOptions(state.config, tags, onChange = { changed -> viewModel.configure { changed } })
        }
    }
    if (confirmingDelete) AlertDialog(
        onDismissRequest = { confirmingDelete = false },
        title = { Text(stringResource(R.string.action_delete)) },
        text = { Text(stringResource(R.string.tip_delete_task, selected.size)) },
        confirmButton = { TextButton(onClick = {
            val ids = selected.toSet()
            confirmingDelete = false
            scope.launch { if (viewModel.delete(ids)) selected = emptyList() }
        }) { Text(stringResource(R.string.action_delete)) } },
        dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text(stringResource(R.string.action_cancel)) } })
}

@Composable
internal fun groupColor(group: TaskGroup, colors: Map<String, Int>): Color = when (group.kind) {
    TaskGroupKind.Overdue -> MaterialTheme.colorScheme.error
    TaskGroupKind.Tag -> Color(assignTagColors(listOfNotNull(group.tag), colors).getValue(requireNotNull(group.tag)))
    TaskGroupKind.Day -> MaterialTheme.colorScheme.primary
    TaskGroupKind.ThisWeek, TaskGroupKind.NextWeek -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
internal fun CollectionHeader(group: TaskGroup, today: LocalDate, colors: Map<String, Int>, collapsed: Boolean,
    emphasized: Boolean, onToggle: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val tint = groupColor(group, colors)
    val title = group.title(context, today)
    val subtitle = group.subtitle(context, today)
    Column(Modifier.padding(top = 8.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .background(if (emphasized) tint.copy(alpha = 0.09f) else Color.Transparent)
            .combinedClickable(onClick = onToggle, role = Role.Button)
            .semantics(mergeDescendants = true) {
                heading()
                stateDescription = resources.getString(if (collapsed) R.string.view_collapsed else R.string.view_expanded)
            }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (group.kind == TaskGroupKind.Tag || group.kind == TaskGroupKind.Overdue) {
                Box(Modifier.width(3.dp).height(20.dp).background(tint))
                Spacer(Modifier.width(10.dp))
            }
            FlowRow(Modifier.weight(1f).padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
                Text(title, style = MaterialTheme.typography.titleSmall,
                    color = if (group.kind == TaskGroupKind.Overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(group.tasks.size.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(16.dp))
            Icon(painterResource(if (collapsed) R.drawable.ic_chevron_right else R.drawable.ic_widget_expand), null, Modifier.size(18.dp))
        }
    }
}

@Composable
internal fun CollectionTaskRow(task: TaskEntity, group: TaskGroup, today: LocalDate, colors: Map<String, Int>,
    selectionMode: Boolean, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit, onComplete: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val tint = groupColor(group, colors)
    val resolved = assignTagColors(task.taskTags, colors)
    val metadata = taskMetadata(context, task, group, today)
    val contentColor = if (task.isCompleted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Column(Modifier.fillMaxWidth().background(if (selected) MaterialTheme.colorScheme.secondaryContainer else tint.copy(alpha = 0.045f))) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = stringResource(R.string.task_edit))
            .semantics {
                this.selected = selected
                contentDescription = (listOf(task.content, metadata).filter { it.isNotEmpty() } + task.taskTags).joinToString(", ")
            }, verticalAlignment = Alignment.CenterVertically) {
            if (selectionMode) Checkbox(checked = selected, onCheckedChange = { onClick() })
            else IconButton(onClick = onComplete) {
                if (task.isCompleted) Icon(painterResource(R.drawable.ic_check_circle),
                    resources.getString(R.string.widget_restore_task, task.content), Modifier.size(24.dp), tint = contentColor)
                else CategoryCompletionRing(task.taskTags.map { Color(resolved.getValue(it)) },
                    Modifier.size(24.dp).semantics { contentDescription = resources.getString(R.string.widget_complete_task, task.content) })
            }
            Column(Modifier.weight(1f).padding(top = 8.dp, bottom = 8.dp, end = 8.dp)) {
                Text(task.content, style = MaterialTheme.typography.bodyLarge, color = contentColor,
                    textDecoration = if (task.isCompleted) TextDecoration.LineThrough else TextDecoration.None,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (metadata.isNotEmpty()) Text(metadata, style = MaterialTheme.typography.bodySmall,
                    color = if (!task.isCompleted && task.dueDay()?.isBefore(today) == true) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            CategoryIconStrip(task.taskTags, onShowAll = onClick, modifier = Modifier.padding(end = 8.dp))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
            modifier = Modifier.padding(start = 48.dp, end = 12.dp))
    }
}
