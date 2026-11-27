package studio.camille.vervetwodo.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

internal fun calendarMillis(value: String): Long? = runCatching {
    LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
}.getOrNull()
internal fun calendarDate(value: Long): String = Instant.ofEpochMilli(value).atZone(ZoneOffset.UTC).toLocalDate().toString()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateInput(value: String, text: Texts, enabled: Boolean = true, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val locale = Locale.forLanguageTag(text("pickerLocale"))
    val label = runCatching { LocalDate.parse(value).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)) }
        .getOrDefault(text("chooseDate"))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { open = true }, enabled = enabled,
            modifier = Modifier.weight(1f).testTag("date-picker-open")) { Text(label) }
        if (value.isNotEmpty()) TextButton(onClick = { onChange("") }, enabled = enabled) { Text(text("clear")) }
    }
    if (open) {
        val state = remember(locale) { DatePickerState(locale, initialSelectedDateMillis = calendarMillis(value), yearRange = 1..9999) }
        DatePickerDialog(onDismissRequest = { open = false },
            confirmButton = { TextButton(enabled = state.selectedDateMillis != null, onClick = {
                state.selectedDateMillis?.let { onChange(calendarDate(it)) }; open = false
            }) { Text(text("apply")) } },
            dismissButton = { TextButton(onClick = { open = false }) { Text(text("cancel")) } }) {
            DatePicker(state, title = { Text(text("dueDate"), Modifier.padding(start = 24.dp, top = 16.dp)) },
                headline = { Text(state.selectedDateMillis?.let { millis ->
                    LocalDate.parse(calendarDate(millis)).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
                } ?: text("chooseDate"), Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.headlineSmall) }, showModeToggle = false)
        }
    }
}

/** Default reminder time (HH:mm) from settings, used when a time field is enabled or left empty. */
internal val LocalDefaultReminderTime = staticCompositionLocalOf { "09:00" }

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
private fun parseTime(value: String) = runCatching { LocalTime.parse(value) }.getOrNull()

@Composable
internal fun TimeInput(value: String, text: Texts, label: String, optional: Boolean = true,
    enabled: Boolean = true, fallback: String = LocalDefaultReminderTime.current, onChange: (String) -> Unit) {
    val default = parseTime(fallback) ?: LocalTime.of(9, 0)
    val time = parseTime(value) ?: default
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (optional) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(value.isNotEmpty(), { onChange(if (it) default.format(timeFormat) else "") }, enabled = enabled,
                    modifier = Modifier.semantics { contentDescription = label })
                Text(label)
            }
        } else Text(label)
        if (!optional || value.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TimePart(time.hour, default.hour, 0..23, text("hour"), enabled, Modifier.weight(1f)) {
                onChange(time.withHour(it).format(timeFormat))
            }
            TimePart(time.minute, default.minute, 0..59, text("minute"), enabled, Modifier.weight(1f)) {
                onChange(time.withMinute(it).format(timeFormat))
            }
        }
    }
}

private fun twoDigits(number: Int) = number.toString().padStart(2, '0')

/**
 * Interprets an edit of a two-digit time field. A full field is overtyped by the newly typed digit,
 * so typing "1" then "0" over "09" yields 10 whatever the caret position.
 */
internal fun timePartEdit(previous: String, next: TextFieldValue, range: IntRange): String? {
    if (next.text.any { it !in '0'..'9' }) return null
    if (next.text.length <= 2 && (next.text.isEmpty() || next.text.toInt() in range)) return next.text
    val typed = next.text.getOrNull(next.selection.start - 1)?.takeIf { next.text.length > previous.length } ?: return null
    return typed.toString().takeIf { it.toInt() in range }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePart(value: Int, default: Int, range: IntRange, label: String, enabled: Boolean, modifier: Modifier, onChange: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(TextFieldValue(twoDigits(value))) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(value) {
        if (draft.text.toIntOrNull() != value && (draft.text.isNotEmpty() || !focused)) draft = TextFieldValue(twoDigits(value), TextRange(2))
    }
    ExposedDropdownMenuBox(expanded, { if (enabled) expanded = it }, modifier) {
        OutlinedTextField(draft, { next ->
            val accepted = timePartEdit(draft.text, next, range) ?: return@OutlinedTextField
            draft = if (accepted == next.text) next else TextFieldValue(accepted, TextRange(accepted.length))
            onChange(accepted.toIntOrNull() ?: default)
        }, Modifier.fillMaxWidth().onFocusChanged {
            if (it.isFocused && !focused) draft = TextFieldValue(draft.text, TextRange(0, draft.text.length))
            if (!it.isFocused && focused) draft = TextFieldValue(twoDigits(value), TextRange(2))
            focused = it.isFocused
        }.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
            enabled = enabled, singleLine = true, label = { Text(label) },
            placeholder = { Text(twoDigits(default)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded,
                Modifier.menuAnchor(ExposedDropdownMenuAnchorType.SecondaryEditable)) })
        ExposedDropdownMenu(expanded, { expanded = false }) {
            range.forEach { number -> DropdownMenuItem(text = { Text(twoDigits(number)) },
                onClick = { draft = TextFieldValue(twoDigits(number), TextRange(2)); onChange(number); expanded = false }) }
        }
    }
}

@Composable
internal fun TimeZoneInput(value: String, text: Texts, enabled: Boolean, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text("${text("timeZone")}: ${value.ifEmpty { ZoneId.systemDefault().id }.replace('_', ' ')}")
    }
    if (open) {
        var query by remember { mutableStateOf("") }
        val zones = remember { (listOf(ZoneId.systemDefault().id, "UTC") + ZoneId.getAvailableZoneIds().sorted()).distinct() }
        val matches = zones.filter { it.replace('_', ' ').contains(query.trim().replace('_', ' '), ignoreCase = true) }
        AlertDialog(onDismissRequest = { open = false }, title = { Text(text("timeZone")) }, text = {
            Column(Modifier.widthIn(min = 280.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, label = { Text(text("searchTimeZone")) }, singleLine = true)
                LazyColumn(Modifier.height(280.dp)) {
                    if (matches.isEmpty()) item { Text(text("noTimeZones")) }
                    items(matches, key = { it }) { zone ->
                        TextButton(onClick = { onChange(zone); open = false }, modifier = Modifier.fillMaxWidth()) {
                            Text(zone.replace('_', ' '), Modifier.weight(1f))
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { open = false }) { Text(text("cancel")) } })
    }
}
