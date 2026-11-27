package cn.super12138.todo.ui.pages.detail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.super12138.todo.R
import cn.super12138.todo.logic.database.TaskEntity
import cn.super12138.todo.logic.database.taskTags
import cn.super12138.todo.logic.formatDueTime
import cn.super12138.todo.logic.assignTagColors
import cn.super12138.todo.logic.model.Priority
import cn.super12138.todo.logic.model.dueDay
import cn.super12138.todo.ui.components.PriorityIcon
import cn.super12138.todo.ui.components.TopAppBarScaffold
import com.mikepenz.markdown.m3.Markdown
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun TaskDetailPage(
    taskId: Int,
    onBack: () -> Unit,
    onEdit: (TaskEntity) -> Unit,
    viewModel: TaskDetailViewModel = koinViewModel { parametersOf(taskId) }
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val error = stringResource(R.string.task_update_failed)
    LaunchedEffect(viewModel, error) {
        viewModel.saveErrors.collect { snackbar.showSnackbar(error) }
    }
    TaskDetailScreen(state, onBack, onEdit, viewModel::setCompleted,
        viewModel::setSubtaskCompleted, viewModel::reload, snackbar)
}

@Composable
fun TaskDetailScreen(
    state: TaskDetailUiState,
    onBack: () -> Unit,
    onEdit: (TaskEntity) -> Unit,
    onCompleted: (Boolean) -> Unit,
    onSubtaskCompleted: (String, Boolean) -> Unit,
    onRetry: () -> Unit = {},
    snackbar: SnackbarHostState = remember { SnackbarHostState() }
) {
    val task = state.task
    TopAppBarScaffold(
        topBar = {},
        snackbarHost = { SnackbarHost(snackbar) }
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            when {
                state.isLoading || state.loadFailed || task == null -> Column(Modifier.fillMaxWidth().padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.action_back))
                    }
                    if (state.isLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else {
                        Text(stringResource(if (state.loadFailed) R.string.task_load_failed else R.string.task_not_found))
                        if (state.loadFailed) TextButton(onClick = onRetry) { Text(stringResource(R.string.overview_retry)) }
                    }
                }
                else -> TaskDetailContent(task, state.tagColors, !state.isSaving, onBack, onEdit, onCompleted, onSubtaskCompleted)
            }
        }
    }
}

@Composable
private fun TaskDetailStatus(task: TaskEntity, enabled: Boolean, onCompleted: (Boolean) -> Unit) {
    val label = stringResource(if (task.isCompleted) R.string.task_status_completed else R.string.task_status_todo)
    val action = stringResource(if (task.isCompleted) R.string.widget_restore_task else R.string.widget_complete_task, task.content)
    Row(Modifier.heightIn(min = 48.dp).clip(MaterialTheme.shapes.small)
        .toggleable(value = task.isCompleted, enabled = enabled, role = Role.Checkbox, onValueChange = onCompleted)
        .semantics { contentDescription = action; stateDescription = label }
        .testTag("task-detail-status")
        .padding(horizontal = 8.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = task.isCompleted, onCheckedChange = null, enabled = enabled,
            modifier = Modifier.size(24.dp).clearAndSetSemantics {})
        Text(label, style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
    }
}

@Composable
private fun TaskDetailDate(task: TaskEntity) {
    val locale = LocalConfiguration.current.locales[0]
    val day = task.dueDay()
    val today = LocalDate.now()
    val date = day?.let {
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale,
            if (it.year == today.year) "MMMEd" else "yMMMEd")
        it.format(DateTimeFormatter.ofPattern(pattern, locale))
    }
    val noDate = stringResource(R.string.view_no_date)
    val time = task.dueTimeMinutes?.takeIf { day != null }?.let(::formatDueTime)
    val dateTime = if (time != null) stringResource(R.string.task_detail_date_time, date.orEmpty(), time) else date ?: noDate
    val overdue = !task.isCompleted && day?.isBefore(today) == true
    val visible = if (overdue) stringResource(R.string.task_detail_overdue, dateTime) else dateTime
    val fullDate = day?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale))
    val fullDateTime = if (time != null) stringResource(R.string.task_detail_date_time, fullDate.orEmpty(), time) else fullDate ?: noDate
    val description = when {
        day == null -> noDate
        overdue -> stringResource(R.string.task_detail_overdue, fullDateTime)
        else -> stringResource(R.string.task_detail_due_accessibility, fullDateTime)
    }
    Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 12.dp)
        .testTag("task-detail-date").clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.ic_agenda), null, Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(visible, style = MaterialTheme.typography.bodyMedium,
            color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskDetailContent(
    task: TaskEntity,
    tagColors: Map<String, Int>,
    enabled: Boolean,
    onBack: () -> Unit,
    onEdit: (TaskEntity) -> Unit,
    onCompleted: (Boolean) -> Unit,
    onSubtaskCompleted: (String, Boolean) -> Unit
) {
    val resolvedTagColors = remember(task.taskTags, tagColors) { assignTagColors(task.taskTags, tagColors) }
    LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.action_back))
                }
                SelectionContainer(Modifier.weight(1f).padding(top = 8.dp)) {
                    Text(task.content, style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.semantics { heading() })
                }
                TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
                    tooltip = { PlainTooltip { Text(stringResource(R.string.task_detail_edit)) } },
                    state = rememberTooltipState()) {
                    IconButton(onClick = { onEdit(task) }, enabled = enabled, modifier = Modifier.size(48.dp)) {
                        Icon(painterResource(R.drawable.ic_edit_task), stringResource(R.string.task_detail_edit),
                            tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    TaskDetailStatus(task, enabled, onCompleted)
                    TaskDetailDate(task)
                }
                cn.super12138.todo.logic.TaskRecurrence.decode(task.recurrence)?.let { rule ->
                    DetailMetadata(stringResource(R.string.repeat_title),
                        cn.super12138.todo.ui.pages.editor.components.recurrenceSummary(rule))
                }
                val priority = Priority.fromFloat(task.priority)
                if (priority != Priority.Default || task.taskTags.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (priority != Priority.Default) Row(Modifier.heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PriorityIcon(priority, modifier = Modifier.clearAndSetSemantics {})
                        Text(stringResource(priority.nameRes), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    task.taskTags.forEach { tag ->
                        Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
                            cn.super12138.todo.ui.components.EditableTag(tag, resolvedTagColors.getValue(tag), enabled = enabled)
                        }
                    }
                }
            }
        }
        if (task.details.isNotBlank()) item {
            DetailHeading(stringResource(R.string.task_details_heading))
            SelectionContainer {
                Markdown(task.details, modifier = Modifier.fillMaxWidth())
            }
        }
        item {
            DetailHeading(stringResource(R.string.task_subtasks))
            if (task.subtasks.isEmpty()) {
                Text(stringResource(R.string.task_subtasks_empty), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { onEdit(task) }, enabled = enabled) {
                    Icon(painterResource(R.drawable.ic_add), null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.task_subtasks_add))
                }
            } else {
                val completed = task.subtasks.count { it.isCompleted }
                Text(stringResource(R.string.task_subtasks_progress, completed, task.subtasks.size),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LinearProgressIndicator(progress = { completed.toFloat() / task.subtasks.size },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp))
            }
        }
        items(task.subtasks, key = { it.id }) { subtask ->
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .toggleable(value = subtask.isCompleted, enabled = enabled, role = Role.Checkbox,
                    onValueChange = { onSubtaskCompleted(subtask.id, it) })
                .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = subtask.isCompleted, onCheckedChange = null, enabled = enabled,
                    modifier = Modifier.size(48.dp))
                Text(subtask.content, style = MaterialTheme.typography.bodyLarge,
                    textDecoration = if (subtask.isCompleted) TextDecoration.LineThrough else null,
                    color = if (subtask.isCompleted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f).padding(end = 8.dp))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun DetailHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(top = 24.dp, bottom = 12.dp).semantics { heading() })
}

@Composable
private fun DetailMetadata(label: String, value: String) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
