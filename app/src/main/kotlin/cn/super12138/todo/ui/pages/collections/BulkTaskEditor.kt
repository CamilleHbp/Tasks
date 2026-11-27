package cn.super12138.todo.ui.pages.collections

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cn.super12138.todo.R
import cn.super12138.todo.logic.*
import cn.super12138.todo.logic.database.TaskEntity
import cn.super12138.todo.logic.model.Priority
import cn.super12138.todo.ui.components.CheckboxWithLabel
import cn.super12138.todo.ui.components.DueTimeDialog
import cn.super12138.todo.ui.pages.editor.components.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BulkTaskEditor(tasks: List<TaskEntity>, tags: List<String>, colors: Map<String, Int>, defaultTime: Int,
    onDismiss: () -> Unit, onApply: suspend (BulkTaskEdit) -> Boolean, scheduling: SchedulingPreferences = SchedulingPreferences()) {
    var editTags by remember { mutableStateOf(false) }
    var editPriority by remember { mutableStateOf(false) }
    var editDate by remember { mutableStateOf(false) }
    var selectedTags by remember { mutableStateOf(emptyList<String>()) }
    var tagAction by remember { mutableStateOf(BulkTagAction.Add) }
    var priority by remember { mutableStateOf(Priority.fromFloat(tasks.map { it.priority }.distinct().singleOrNull() ?: 0f)) }
    var date by remember { mutableStateOf(tasks.map { it.dueDateMillis }.distinct().singleOrNull()) }
    var time by remember { mutableStateOf(tasks.map { it.dueTimeMinutes }.distinct().singleOrNull() ?: defaultTime) }
    var hasTime by remember { mutableStateOf(true) }
    var timePicker by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val changed = editTags && (selectedTags.isNotEmpty() || tagAction == BulkTagAction.Replace) || editPriority || editDate
    ModalBottomSheet(onDismissRequest = { if (!saving) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 24.dp)) {
            Text(stringResource(R.string.bulk_edit_title, tasks.size), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.bulk_edit_hint), style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp, bottom = 12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(R.string.action_cancel)) }
                Spacer(Modifier.width(8.dp))
                Button(enabled = changed && !saving && tasks.isNotEmpty(), onClick = {
                    saving = true
                    scope.launch {
                        val applied = onApply(BulkTaskEdit(tags = selectedTags.takeIf { editTags }, tagAction = tagAction,
                            priority = priority.value.takeIf { editPriority }, changeDueDate = editDate,
                            dueDateMillis = date, dueTimeMinutes = time.takeIf { hasTime }))
                        saving = false
                        if (applied) onDismiss() else failed = true
                    }
                }) { Text(stringResource(if (saving) R.string.action_saving else R.string.bulk_apply)) }
            }
            if (failed) Text(stringResource(R.string.view_error), color = MaterialTheme.colorScheme.error)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CheckboxWithLabel(stringResource(R.string.bulk_change_tags), editTags,
                    { if (!saving) editTags = it }, Modifier.fillMaxWidth())
                if (editTags) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        BulkTagAction.entries.forEach { action ->
                            FilterChip(selected = action == tagAction, enabled = !saving, onClick = { tagAction = action },
                                label = { Text(stringResource(when (action) {
                                    BulkTagAction.Add -> R.string.bulk_tags_add
                                    BulkTagAction.Remove -> R.string.bulk_tags_remove
                                    BulkTagAction.Replace -> R.string.bulk_tags_replace
                                })) }, modifier = Modifier.heightIn(min = 48.dp))
                        }
                    }
                    TagSelectionCloud(tags, selectedTags, colors, onToggle = {
                        selectedTags = if (it in selectedTags) selectedTags - it else selectedTags + it
                    }, enabled = !saving)
                    if (tagAction == BulkTagAction.Replace && selectedTags.isEmpty()) Text(stringResource(R.string.bulk_tags_clear),
                        style = MaterialTheme.typography.bodyMedium)
                }
                HorizontalDivider()
                CheckboxWithLabel(stringResource(R.string.bulk_change_priority), editPriority,
                    { if (!saving) editPriority = it }, Modifier.fillMaxWidth())
                if (editPriority) PrioritySlider(priority, { priority = it }, enabled = !saving)
                HorizontalDivider()
                CheckboxWithLabel(stringResource(R.string.bulk_change_date), editDate,
                    { if (!saving) editDate = it }, Modifier.fillMaxWidth())
                if (editDate) {
                    DueDateChooser(date, { date = it }, enabled = !saving, preferences = scheduling)
                    if (date != null) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { timePicker = true }, enabled = !saving) {
                            Text(if (hasTime) stringResource(R.string.due_time_value, formatDueTime(time)) else stringResource(R.string.add_due_time))
                        }
                        if (hasTime) TextButton(onClick = { hasTime = false }, enabled = !saving) { Text(stringResource(R.string.remove_due_time)) }
                    } else Text(stringResource(R.string.bulk_date_clear), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
    if (timePicker) DueTimeDialog(time, initialDial = scheduling.timePickerDial, onConfirm = { time = it; hasTime = true; timePicker = false },
        onDismiss = { timePicker = false })
}
