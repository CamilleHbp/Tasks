package cn.super12138.todo.ui.pages.editor.components

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import cn.super12138.todo.R
import cn.super12138.todo.logic.*
import cn.super12138.todo.logic.model.Priority
import cn.super12138.todo.ui.components.TagManagementDialog
import cn.super12138.todo.ui.components.PriorityIcon
import cn.super12138.todo.utils.toLocalDate
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorSchedule(dateMillis: Long?, timeMinutes: Int?, defaultTime: Int, rule: TaskRecurrence?,
    onQuickDate: (Long?) -> Unit, onSchedule: (Long?, Int?) -> Unit,
    onRecurrence: (TaskRecurrence?) -> Unit, enabled: Boolean, preferences: SchedulingPreferences = SchedulingPreferences(),
    rollover: Boolean = false, onRollover: (Boolean) -> Unit = {}) {
    var showDate by rememberSaveable { mutableStateOf(false) }
    var showRepeat by rememberSaveable { mutableStateOf(false) }
    var resumeRepeat by rememberSaveable { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val today = LocalDate.now()
    val locale = LocalConfiguration.current.locales[0]
    val dateLabel = dateMillis?.toLocalDate()?.format(DateTimeFormatter.ofPattern(
        DateFormat.getBestDateTimePattern(locale, if (dateMillis.toLocalDate().year == today.year) "MMMEd" else "yMMMEd"), locale))
    val dateSummary = dateLabel?.let { if (timeMinutes == null) it else "$it · ${formatDueTime(timeMinutes)}" }
        ?: stringResource(R.string.editor_date_time)
    val repeatSummary = rule?.let { recurrenceSummary(it) } ?: stringResource(R.string.editor_not_recurring)
    val dateAction = if (dateMillis == null) stringResource(R.string.editor_date_time)
        else stringResource(R.string.editor_edit_schedule, dateSummary)
    val repeatAction = stringResource(R.string.editor_edit_recurrence, repeatSummary)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.label_due_date), style = MaterialTheme.typography.titleSmall)
            if (dateMillis != null) {
                Text(dateSummary, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End)
                IconButton(onClick = { onSchedule(null, null) }, enabled = enabled) {
                    Icon(painterResource(R.drawable.ic_close), stringResource(R.string.remove_due_date), Modifier.size(20.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(R.string.time_today to today, R.string.time_tomorrow to today.plusDays(1),
                    R.string.time_next_week to preferences.nextWeek(today, locale)).forEach { (label, day) ->
                    FilterChip(selected = dateMillis?.toLocalDate() == day, enabled = enabled,
                        onClick = { onQuickDate(day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()) },
                        label = { Text(stringResource(label)) }, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
            OutlinedIconButton(onClick = { keyboard?.hide(); showDate = true }, enabled = enabled,
                modifier = Modifier.size(48.dp).testTag("editor-date-time")) {
                Icon(painterResource(R.drawable.ic_agenda), dateAction, Modifier.size(22.dp))
            }
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { keyboard?.hide(); showRepeat = true }, enabled = enabled,
            colors = ButtonDefaults.textButtonColors(contentColor = if (rule == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary),
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
            modifier = Modifier.heightIn(min = 48.dp).testTag("editor-recurrence")
                .semantics { contentDescription = repeatAction }) {
            Icon(painterResource(if (rule == null) R.drawable.ic_repeat_off else R.drawable.ic_repeat), null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(repeatSummary, color = if (rule == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
        }
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.heightIn(min = 48.dp).testTag("editor-rollover")
                .toggleable(value = rollover, enabled = enabled, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onRollover)
                .padding(horizontal = 4.dp, vertical = 8.dp).alpha(if (enabled) 1f else 0.38f)) {
                val color = if (rollover) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                Icon(painterResource(if (rollover) R.drawable.ic_rollover else R.drawable.ic_rollover_off),
                    null, Modifier.size(20.dp), tint = color)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.rollover_title), color = color, style = MaterialTheme.typography.labelLarge)
        }
        }
    }
    if (showDate) EditorDateTimeSheet(dateMillis, timeMinutes, defaultTime, preferences,
        onConfirm = { date, time ->
            onSchedule(date, time); showDate = false
            if (resumeRepeat && date != null) showRepeat = true
            resumeRepeat = false
        }, onDismiss = { showDate = false; showRepeat = resumeRepeat; resumeRepeat = false })
    if (showRepeat) EditorRecurrenceDialog(rule, dateMillis,
        onConfirm = { onRecurrence(it); showRepeat = false }, onDismiss = { showRepeat = false },
        onChooseDate = { showRepeat = false; resumeRepeat = true; showDate = true })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorDateTimeSheet(dateMillis: Long?, timeMinutes: Int?, defaultTime: Int, preferences: SchedulingPreferences,
    onConfirm: (Long?, Int?) -> Unit, onDismiss: () -> Unit) {
    val locale = preferences.calendarLocale(LocalConfiguration.current.locales[0])
    var hour by rememberSaveable { mutableStateOf(((timeMinutes ?: defaultTime) / 60).toString().padStart(2, '0')) }
    var minute by rememberSaveable { mutableStateOf(((timeMinutes ?: defaultTime) % 60).toString().padStart(2, '0')) }
    val validTime = hour.toIntOrNull() in 0..23 && minute.toIntOrNull() in 0..59
    var showTimeDial by rememberSaveable { mutableStateOf(preferences.timePickerDial) }
    val clock = key(showTimeDial) {
        rememberTimePickerState(initialHour = hour.toIntOrNull()?.coerceIn(0, 23) ?: 0,
            initialMinute = minute.toIntOrNull()?.coerceIn(0, 59) ?: 0, is24Hour = true)
    }
    var includeTime by rememberSaveable { mutableStateOf(timeMinutes != null || dateMillis == null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        val keyboard = LocalSoftwareKeyboardController.current
        val focusManager = LocalFocusManager.current
        var revealTimeDial by remember { mutableStateOf(false) }
        val dialBringIntoView = remember { BringIntoViewRequester() }
        LaunchedEffect(showTimeDial, includeTime, revealTimeDial) {
            if (showTimeDial && includeTime && revealTimeDial) {
                withFrameNanos { }
                focusManager.clearFocus(force = true)
                keyboard?.hide()
                dialBringIntoView.bringIntoView()
                revealTimeDial = false
            }
        }
        val timeLabel = stringResource(R.string.editor_include_time)
        // Material calendars require 360 dp; input mode preserves full date control below that width.
        val compact = LocalConfiguration.current.screenWidthDp < 360
        val today = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        var selectedDate by rememberSaveable { mutableStateOf<Long?>(pickerDateMillis(dateMillis ?: today)) }
        var displayedMonth by rememberSaveable { mutableStateOf(selectedDate) }
        var inputMode by rememberSaveable { mutableStateOf(!preferences.datePickerCalendar) }
        val picker = remember(locale, compact) { DatePickerState(locale = locale, initialSelectedDateMillis = selectedDate,
            initialDisplayedMonthMillis = displayedMonth, initialDisplayMode = if (compact || inputMode) DisplayMode.Input else DisplayMode.Picker) }
        LaunchedEffect(picker) {
            snapshotFlow { Triple(picker.selectedDateMillis, picker.displayedMonthMillis, picker.displayMode) }.collect {
                selectedDate = it.first; displayedMonth = it.second; inputMode = it.third == DisplayMode.Input
            }
        }
        Column(Modifier.fillMaxWidth().testTag("editor-schedule-dialog")) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.editor_date_time), Modifier.weight(1f).semantics { heading() },
                    style = MaterialTheme.typography.titleLarge)
                if (!compact) IconButton(onClick = { picker.displayMode = if (inputMode) DisplayMode.Picker else DisplayMode.Input }) {
                    Icon(painterResource(if (inputMode) R.drawable.ic_agenda else R.drawable.ic_edit_task),
                        stringResource(if (inputMode) R.string.editor_pick_date else R.string.editor_type_date))
                }
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                DatePicker(picker, title = null, headline = null, showModeToggle = false, focusRequester = null,
                    colors = DatePickerDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp).heightIn(min = 56.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.editor_include_time), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = includeTime, onCheckedChange = { includeTime = it },
                        modifier = Modifier.testTag("editor-include-time").semantics {
                            contentDescription = timeLabel
                        })
                    if (includeTime) IconButton(enabled = showTimeDial || validTime, onClick = {
                        if (showTimeDial) {
                            hour = clock.hour.toString().padStart(2, '0')
                            minute = clock.minute.toString().padStart(2, '0')
                        } else {
                            focusManager.clearFocus(force = true)
                            keyboard?.hide()
                        }
                        revealTimeDial = !showTimeDial
                        showTimeDial = !showTimeDial
                    }, modifier = Modifier.testTag("editor-time-mode")) {
                        Icon(painterResource(if (showTimeDial) R.drawable.ic_edit_task else R.drawable.ic_clock),
                            stringResource(if (showTimeDial) R.string.editor_type_time else R.string.editor_pick_time))
                    }
                }
                if (includeTime && showTimeDial) Box(Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center) {
                    TimePicker(state = clock, layoutType = TimePickerLayoutType.Vertical,
                        modifier = Modifier.bringIntoViewRequester(dialBringIntoView).testTag("editor-time-dial"))
                }
                if (includeTime && !showTimeDial) Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(hour, { if (it.length <= 2 && it.all(Char::isDigit)) hour = it },
                            label = { Text(stringResource(R.string.editor_hour)) }, singleLine = true,
                            isError = hour.toIntOrNull() !in 0..23,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            textStyle = MaterialTheme.typography.headlineSmall.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                            modifier = Modifier.width(96.dp).testTag("editor-hour"))
                        Text(":", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.clearAndSetSemantics { })
                        OutlinedTextField(minute, { if (it.length <= 2 && it.all(Char::isDigit)) minute = it },
                            label = { Text(stringResource(R.string.editor_minute)) }, singleLine = true,
                            isError = minute.toIntOrNull() !in 0..59,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            textStyle = MaterialTheme.typography.headlineSmall.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                            modifier = Modifier.width(96.dp).testTag("editor-minute"))
                    }
                    if (!validTime) Text(stringResource(R.string.editor_invalid_time), color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
            }
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                TextButton(enabled = picker.selectedDateMillis != null && (!includeTime || showTimeDial || validTime), onClick = {
                    picker.selectedDateMillis?.let {
                        val minutes = if (!includeTime) null else if (showTimeDial) clock.hour * 60 + clock.minute
                            else hour.toInt() * 60 + minute.toInt()
                        onConfirm(localDateFromPicker(it), minutes)
                    }
                }) { Text(stringResource(R.string.action_confirm)) }
            }
        }
    }
}

@Composable
private fun EditorRecurrenceDialog(rule: TaskRecurrence?, dateMillis: Long?,
    onConfirm: (TaskRecurrence?) -> Unit, onDismiss: () -> Unit, onChooseDate: () -> Unit) {
    var repeating by rememberSaveable { mutableStateOf(rule != null) }
    var interval by rememberSaveable { mutableStateOf((rule?.interval ?: 1).toString()) }
    var frequency by rememberSaveable { mutableStateOf(rule?.frequency ?: RepeatFrequency.Daily) }
    var unitsOpen by remember { mutableStateOf(false) }
    val number = interval.toIntOrNull()
    val valid = number != null && number in 1..999
    val unitLabels = mapOf(RepeatFrequency.Daily to R.string.editor_repeat_unit_days,
        RepeatFrequency.Weekly to R.string.editor_repeat_unit_weeks,
        RepeatFrequency.Monthly to R.string.editor_repeat_unit_months,
        RepeatFrequency.Yearly to R.string.editor_repeat_unit_years)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.repeat_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (dateMillis == null) Text(stringResource(R.string.repeat_needs_date))
                else {
                    listOf(false to R.string.editor_not_recurring, true to R.string.editor_repeat_every).forEach { (value, label) ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .selectable(selected = repeating == value, role = Role.RadioButton, onClick = { repeating = value }),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            RadioButton(selected = repeating == value, onClick = null)
                            Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    if (repeating) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(value = interval,
                                onValueChange = { if (it.length <= 3 && it.all(Char::isDigit)) interval = it },
                                label = { Text(stringResource(R.string.editor_interval)) },
                                isError = !valid, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f).testTag("editor-repeat-interval"))
                            Box(Modifier.weight(1f)) {
                                OutlinedButton(onClick = { unitsOpen = true },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("editor-repeat-unit")) {
                                    Text(stringResource(unitLabels.getValue(frequency)), Modifier.weight(1f))
                                    Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(20.dp).rotate(90f))
                                }
                                DropdownMenu(unitsOpen, onDismissRequest = { unitsOpen = false }) {
                                    RepeatFrequency.entries.forEach { unit ->
                                        DropdownMenuItem(text = { Text(stringResource(unitLabels.getValue(unit))) },
                                            onClick = { frequency = unit; unitsOpen = false })
                                    }
                                }
                            }
                        }
                        if (!valid) Text(stringResource(R.string.editor_repeat_interval_hint), color = MaterialTheme.colorScheme.error)
                        if (frequency == RepeatFrequency.Monthly || frequency == RepeatFrequency.Yearly)
                            Text(stringResource(R.string.repeat_explanation), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            if (dateMillis == null) TextButton(onClick = onChooseDate) { Text(stringResource(R.string.choose_date)) }
            else TextButton(enabled = !repeating || valid, onClick = {
                onConfirm(if (!repeating) null else TaskRecurrence(frequency, number!!,
                    rule?.takeIf { it.frequency == frequency }?.anchor ?: dateMillis.toLocalDate().toString()))
            }) { Text(stringResource(R.string.action_confirm)) }
        }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorTags(tags: List<String>, selected: List<String>, colors: Map<String, Int>,
    onToggle: (String) -> Unit, onAdd: (String) -> Unit, enabled: Boolean,
    onReorder: ((List<String>) -> Unit)? = null) {
    var showPicker by rememberSaveable { mutableStateOf(false) }
    var showCreate by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val catalog = remember(tags, selected) {
        (tags + selected).distinct()
    }
    val latestReorder by rememberUpdatedState(onReorder)
    val reordering = if (onReorder != null && enabled && catalog.size > 1) {
        remember(catalog, expanded) { EditorTagReordering(catalog) { latestReorder?.invoke(it) } }
    } else null
    Column(Modifier.fillMaxWidth().testTag("editor-tags-preview")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.view_tags), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            IconButton(onClick = { keyboard?.hide(); showPicker = true }, enabled = enabled) {
                Icon(painterResource(R.drawable.ic_search), stringResource(R.string.editor_find_tag), Modifier.size(20.dp))
            }
            IconButton(onClick = {
                keyboard?.hide(); showCreate = true
            }, enabled = enabled) {
                Icon(painterResource(R.drawable.ic_add), stringResource(R.string.tag_create), Modifier.size(20.dp))
            }
        }
        if (catalog.isEmpty()) Text(stringResource(R.string.editor_no_tags), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        else if (expanded) TagSelectionCloud(catalog, selected, colors, onToggle, enabled, reordering)
        else TwoRowTagPreview(catalog, selected, onToggle, { expanded = true }, enabled, reordering)
    }
    if (showCreate) TagManagementDialog(tag = "", onDismiss = { showCreate = false }, onSaved = onAdd)
    if (showPicker) ModalBottomSheet(onDismissRequest = { showPicker = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.view_tags), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { showPicker = false }) { Text(stringResource(R.string.action_confirm)) }
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                EditorTagChooser(tags, selected, colors, query, { query = it }, onToggle,
                    onAdd = { onAdd(query); query = "" }, enabled = enabled)
            }
        }
    }
}

// Measure real chip widths, including translated text and font scale, before choosing two rows.
// The overflow control counts all omitted tags and expands them inline for this editing session.
@Composable
private fun TwoRowTagPreview(tags: List<String>, selected: List<String>,
    onToggle: (String) -> Unit, onMore: () -> Unit, enabled: Boolean,
    reordering: EditorTagReordering?) {
    val moreLabel = stringResource(R.string.editor_more_tags)
    SubcomposeLayout(Modifier.fillMaxWidth()) { constraints ->
        val gap = 8.dp.roundToPx()
        val width = constraints.maxWidth
        val loose = constraints.copy(minWidth = 0, minHeight = 0, maxHeight = Constraints.Infinity)
        val chips = subcompose("chips") {
            tags.forEach { tag ->
                key(tag) {
                    EditorTagChip(tag, tag in selected, { onToggle(tag) }, enabled, reordering, singleLine = true)
                }
            }
        }.map { it.measure(loose.copy(maxWidth = minOf(width, 240.dp.roundToPx()))) }
        fun positions(count: Int, extraWidth: Int = 0): List<Pair<Int, Int>>? {
            var x = 0; var y = 0; var rowHeight = 0; var row = 0
            val result = mutableListOf<Pair<Int, Int>>()
            val widths = chips.take(count).map { it.width } + if (extraWidth > 0) listOf(extraWidth) else emptyList()
            widths.forEachIndexed { index, itemWidth ->
                if (x > 0 && x + itemWidth > width) { row++; x = 0; y += rowHeight + gap; rowHeight = 0 }
                if (row >= 2) return null
                result += x to y
                rowHeight = maxOf(rowHeight, chips.getOrNull(index)?.height ?: 48.dp.roundToPx())
                x += itemWidth + gap
            }
            return result
        }
        var count = chips.size
        val full = positions(count)
        val overflowWidth = minOf(width, 80.dp.roundToPx())
        var placed = full
        if (placed == null) {
            count = 0
            while (count < chips.size && positions(count + 1, overflowWidth) != null) count++
            placed = positions(count, overflowWidth)
        }
        val overflow = if (count < chips.size) subcompose("more") {
            TextButton(onClick = onMore, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)
                .semantics { contentDescription = moreLabel }.testTag("editor-more-tags")) { Text("+${chips.size - count}") }
        }.single().measure(loose.copy(minWidth = overflowWidth, maxWidth = overflowWidth)) else null
        val locations = placed.orEmpty()
        val height = locations.mapIndexed { index, (_, y) -> y + if (index < count) chips[index].height else overflow!!.height }.maxOrNull() ?: 0
        layout(width, height) {
            reordering?.retainVisibleTags(tags.take(count))
            chips.take(count).forEachIndexed { index, chip -> chip.placeRelative(locations[index].first, locations[index].second) }
            overflow?.placeRelative(locations.last().first, locations.last().second)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorPrioritySlider(priority: Priority, onChange: (Priority) -> Unit, enabled: Boolean) {
    val interaction = remember { MutableInteractionSource() }
    val sliderState = remember { SliderState(value = priority.value, steps = 3, trackRange = -2f..2f) }
    SideEffect { sliderState.value = priority.value }
    val dragged by interaction.collectIsDraggedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    var recentlySelected by remember { mutableStateOf(false) }
    LaunchedEffect(priority, recentlySelected) { if (recentlySelected) { kotlinx.coroutines.delay(1500); recentlySelected = false } }
    val label = stringResource(R.string.label_priority)
    val valueLabel = stringResource(R.string.priority_value, priority.value.toInt(), stringResource(priority.nameRes))
    Column(Modifier.fillMaxWidth().testTag("editor-priority")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Box(Modifier.weight(1f).clearAndSetSemantics { }, contentAlignment = Alignment.CenterEnd) {
                Priority.entries.forEach { item ->
                    Text(stringResource(item.nameRes), Modifier.alpha(if (item == priority && (dragged || pressed || focused || recentlySelected)) 1f else 0f),
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.End)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(48.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
            .alpha(if (enabled) 1f else 0.38f)) {
            Row(Modifier.fillMaxWidth().clearAndSetSemantics { }, horizontalArrangement = Arrangement.SpaceBetween) {
                Priority.entries.forEach { item ->
                    Box(Modifier.size(48.dp).padding(4.dp)
                        .background(if (item == priority) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape),
                        contentAlignment = Alignment.Center) {
                        if (item == priority) PriorityIcon(item, tint = MaterialTheme.colorScheme.onPrimary, contentDescription = null)
                        else PriorityIcon(item, contentDescription = null)
                    }
                }
            }
            // The native slider owns touch, keyboard, and accessibility. Its 48 dp thumb
            // aligns its five stops with the icon centers, including both endpoints.
            Slider(state = sliderState, onValueChange = {
                onChange(Priority.fromFloat(it.roundToInt().toFloat()))
                recentlySelected = true
            }, enabled = enabled, interactionSource = interaction,
                thumb = { Spacer(Modifier.size(48.dp)) },
                track = { Spacer(Modifier.fillMaxWidth().height(48.dp)) },
                modifier = Modifier.fillMaxWidth().testTag("editor-priority-slider")
                    .semantics { contentDescription = label; stateDescription = valueLabel })
        }
    }
}
