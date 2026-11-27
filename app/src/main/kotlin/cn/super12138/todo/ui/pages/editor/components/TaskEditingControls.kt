package cn.super12138.todo.ui.pages.editor.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import cn.super12138.todo.R
import cn.super12138.todo.logic.*
import cn.super12138.todo.logic.model.Priority
import cn.super12138.todo.ui.components.PriorityIcon
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

@Composable
internal fun TagSelectionCloud(tags: List<String>, selected: List<String>, colors: Map<String, Int>,
    onToggle: (String) -> Unit, enabled: Boolean = true, reordering: EditorTagReordering? = null) {
    val catalog = (tags + selected).distinct()
    val resolved = assignTagColors(catalog, colors)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        catalog.forEach { tag ->
            key(tag) {
                EditorTagChip(tag, tag in selected, { onToggle(tag) }, enabled, reordering)
            }
        }
    }
}

@Composable
fun PrioritySlider(priority: Priority, onChange: (Priority) -> Unit, enabled: Boolean = true) {
    val label = stringResource(R.string.label_priority)
    val valueLabel = stringResource(R.string.priority_value, priority.value.toInt(), stringResource(priority.nameRes))
    Column {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PriorityIcon(priority, contentDescription = null)
            Text(valueLabel, style = MaterialTheme.typography.bodyLarge)
        }
        Slider(value = priority.value, onValueChange = { onChange(Priority.fromFloat(it.roundToInt().toFloat())) },
            valueRange = -2f..2f, steps = 3, enabled = enabled,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label; stateDescription = valueLabel })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Priority.entries.forEach { item ->
                IconButton(onClick = { onChange(item) }, enabled = enabled,
                    modifier = Modifier.semantics { selected = item == priority }) {
                    PriorityIcon(item, contentDescription = stringResource(R.string.priority_value,
                        item.value.toInt(), stringResource(item.nameRes)))
                }
            }
        }
    }
}

@Composable
fun recurrenceSummary(rule: TaskRecurrence): String {
    val unit = when (rule.frequency) {
        RepeatFrequency.Daily -> R.plurals.repeat_days
        RepeatFrequency.Weekly -> R.plurals.repeat_weeks
        RepeatFrequency.Monthly -> R.plurals.repeat_months
        RepeatFrequency.Yearly -> R.plurals.repeat_years
    }
    val cadence = if (rule.interval == 1) stringResource(when (rule.frequency) {
        RepeatFrequency.Daily -> R.string.repeat_daily
        RepeatFrequency.Weekly -> R.string.repeat_weekly
        RepeatFrequency.Monthly -> R.string.repeat_monthly
        RepeatFrequency.Yearly -> R.string.repeat_yearly
    }) else pluralStringResource(unit, rule.interval, rule.interval)
    val date = LocalDate.parse(rule.anchor)
    return when (rule.frequency) {
        RepeatFrequency.Monthly -> stringResource(R.string.repeat_monthly_summary, cadence, date.dayOfMonth)
        RepeatFrequency.Yearly -> stringResource(R.string.repeat_yearly_summary, cadence,
            date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)))
        else -> cadence
    }
}

@Composable
fun RecurrenceChooser(rule: TaskRecurrence?, dateMillis: Long?, onChange: (TaskRecurrence?) -> Unit,
    enabled: Boolean = true) {
    var editInterval by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.repeat_title), style = MaterialTheme.typography.titleMedium)
        if (dateMillis == null) Text(stringResource(R.string.repeat_needs_date),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = rule == null, onClick = { onChange(null) }, enabled = enabled,
                label = { Text(stringResource(R.string.repeat_none)) }, modifier = Modifier.heightIn(min = 48.dp))
            RepeatFrequency.entries.forEach { frequency ->
                val label = when (frequency) {
                    RepeatFrequency.Daily -> R.string.repeat_daily
                    RepeatFrequency.Weekly -> R.string.repeat_weekly
                    RepeatFrequency.Monthly -> R.string.repeat_monthly
                    RepeatFrequency.Yearly -> R.string.repeat_yearly
                }
                FilterChip(selected = rule?.frequency == frequency, enabled = enabled && dateMillis != null,
                    onClick = { dateMillis?.takeIf { frequency != rule?.frequency }?.let { onChange(TaskRecurrence(frequency, rule?.interval ?: 1,
                        Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().toString())) } },
                    label = { Text(stringResource(label)) }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
        if (rule != null) {
            val summary = recurrenceSummary(rule)
            val editLabel = stringResource(R.string.editor_repeat_interval_edit, summary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { editInterval = true }, enabled = enabled,
                    modifier = Modifier.weight(1f).semantics { contentDescription = editLabel }) {
                    Text(summary, style = MaterialTheme.typography.bodyLarge)
                }
                IconButton(onClick = { onChange(rule.copy(interval = rule.interval - 1)) }, enabled = enabled && rule.interval > 1) {
                    Icon(painterResource(R.drawable.ic_minus), stringResource(R.string.repeat_less))
                }
                IconButton(onClick = { onChange(rule.copy(interval = rule.interval + 1)) }, enabled = enabled && rule.interval < 999) {
                    Icon(painterResource(R.drawable.ic_add), stringResource(R.string.repeat_more))
                }
            }
            if (rule.frequency == RepeatFrequency.Monthly || rule.frequency == RepeatFrequency.Yearly)
                Text(stringResource(R.string.repeat_explanation), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (editInterval && rule != null) {
        var interval by rememberSaveable(rule.interval) { mutableStateOf(rule.interval.toString()) }
        val number = interval.toIntOrNull()
        val valid = number != null && number in 1..999
        val focus = remember { FocusRequester() }
        fun confirm() { if (valid) { onChange(rule.copy(interval = number!!)); editInterval = false } }
        AlertDialog(onDismissRequest = { editInterval = false },
            title = { Text(stringResource(R.string.editor_repeat_interval)) },
            text = {
                OutlinedTextField(value = interval,
                    onValueChange = { if (it.length <= 3 && it.all(Char::isDigit)) interval = it },
                    label = { Text(stringResource(R.string.editor_repeat_interval)) },
                    supportingText = { Text(stringResource(R.string.editor_repeat_interval_hint)) },
                    isError = !valid, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { confirm() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus))
            },
            confirmButton = { TextButton(onClick = ::confirm, enabled = valid) { Text(stringResource(R.string.action_confirm)) } },
            dismissButton = { TextButton(onClick = { editInterval = false }) { Text(stringResource(R.string.action_cancel)) } })
        LaunchedEffect(Unit) { focus.requestFocus() }
    }
}
