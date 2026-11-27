package cn.super12138.todo.ui.pages.collections

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cn.super12138.todo.R
import cn.super12138.todo.logic.model.*
import cn.super12138.todo.ui.widget.upcoming.labelRes

@Composable
fun TaskViewOptions(config: TaskViewConfig, tags: List<String>, onChange: (TaskViewConfig) -> Unit, allowMode: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (allowMode) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TaskViewMode.entries.forEach { mode ->
                FilterChip(selected = config.mode == mode, onClick = { onChange(config.copy(mode = mode)) },
                    label = { Text(stringResource(if (mode == TaskViewMode.Agenda) R.string.view_agenda else R.string.view_tags)) })
            }
        }
        Text(stringResource(R.string.view_status), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TaskStatusFilter.entries.forEach { status ->
                FilterChip(selected = status == config.status, onClick = { onChange(config.copy(status = status)) },
                    label = { Text(stringResource(status.labelRes())) })
            }
        }
        Text(stringResource(R.string.pref_sorting_method), style = MaterialTheme.typography.titleSmall)
        ChoiceMenu(stringResource(config.sort.labelRes()), TaskGroupSort.entries.map { it to stringResource(it.labelRes()) }) {
            onChange(config.copy(sort = it))
        }
        HorizontalDivider()
        if (config.mode == TaskViewMode.Agenda) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.view_individual_days), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.view_days_hint), style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { onChange(config.copy(individualDays = config.individualDays - 1)) }, enabled = config.individualDays > 1) {
                    Icon(painterResource(R.drawable.ic_minus), stringResource(R.string.view_decrease_days))
                }
                Text(config.individualDays.toString(), style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { onChange(config.copy(individualDays = config.individualDays + 1)) }, enabled = config.individualDays < 7) {
                    Icon(painterResource(R.drawable.ic_add), stringResource(R.string.view_increase_days))
                }
            }
            OptionSwitch(stringResource(R.string.view_this_week_toggle), config.thisWeek) { onChange(config.copy(thisWeek = it)) }
            OptionSwitch(stringResource(R.string.view_next_week_toggle), config.nextWeek) { onChange(config.copy(nextWeek = it)) }
            Text(stringResource(R.string.view_grouping_hint), style = MaterialTheme.typography.bodySmall)
            OptionSwitch(stringResource(R.string.view_empty_days), config.showEmptyDays) { onChange(config.copy(showEmptyDays = it)) }
        }
        OptionSwitch(stringResource(R.string.view_emphasize), config.emphasizeGroups) { onChange(config.copy(emphasizeGroups = it)) }
        HorizontalDivider()
        Text(stringResource(R.string.view_filter_tags), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.widget_tags_hint), style = MaterialTheme.typography.bodySmall)
        OptionCheck(stringResource(R.string.widget_all_tags), config.tags.isEmpty()) { onChange(config.copy(tags = emptySet())) }
        (listOf("") + tags).distinct().forEach { tag ->
            OptionCheck(if (tag.isEmpty()) stringResource(R.string.widget_no_tag) else tag, tag in config.tags) {
                onChange(config.copy(tags = if (tag in config.tags) config.tags - tag else config.tags + tag))
            }
        }
        if (config.mode == TaskViewMode.Tags && tags.size > 1) {
            HorizontalDivider()
            Text(stringResource(R.string.view_tag_order), style = MaterialTheme.typography.titleSmall)
            val ordered = (config.tagOrder.filter { it in tags } + tags).distinct()
            ordered.forEachIndexed { index, tag ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tag, modifier = Modifier.weight(1f))
                    IconButton(enabled = index > 0, onClick = {
                        val next = ordered.toMutableList()
                        next[index] = next[index - 1]; next[index - 1] = tag
                        onChange(config.copy(tagOrder = next))
                    }) { Icon(painterResource(R.drawable.ic_widget_collapse), stringResource(R.string.view_move_up, tag)) }
                    IconButton(enabled = index < ordered.lastIndex, onClick = {
                        val next = ordered.toMutableList()
                        next[index] = next[index + 1]; next[index + 1] = tag
                        onChange(config.copy(tagOrder = next))
                    }) { Icon(painterResource(R.drawable.ic_widget_expand), stringResource(R.string.view_move_down, tag)) }
                }
            }
        }
    }
}

@Composable
internal fun <T> ChoiceMenu(label: String, choices: List<Pair<T, String>>, onChoose: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, contentPadding = PaddingValues(horizontal = 12.dp)) {
            Text(label)
            Spacer(Modifier.width(8.dp))
            Icon(painterResource(R.drawable.ic_widget_expand), null, Modifier.size(16.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { (value, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { expanded = false; onChoose(value) })
            }
        }
    }
}

@Composable
private fun OptionSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f).padding(end = 12.dp), style = MaterialTheme.typography.bodyMedium)
        Switch(checked, onCheckedChange = null)
    }
}

@Composable
private fun OptionCheck(label: String, checked: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, role = Role.Checkbox, onValueChange = { onClick() }),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = null)
        Text(label, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

