package cn.super12138.todo.ui.pages.editor.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cn.super12138.todo.R
import cn.super12138.todo.logic.pickerDateMillis
import cn.super12138.todo.logic.localDateFromPicker
import cn.super12138.todo.utils.toLocalDate
import cn.super12138.todo.utils.toLocalDateString
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun DueDateChooser(dateMillis: Long?, onDateChange: (Long?) -> Unit, enabled: Boolean = true, preferences: cn.super12138.todo.logic.SchedulingPreferences = cn.super12138.todo.logic.SchedulingPreferences()) {
    var openDialog by rememberSaveable { mutableStateOf(false) }
    val today = LocalDate.now()
    val locale = preferences.calendarLocale(androidx.compose.ui.platform.LocalConfiguration.current.locales[0])
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.label_due_date), style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(R.string.time_today to today, R.string.time_tomorrow to today.plusDays(1),
                R.string.time_next_week to preferences.nextWeek(today, locale)).forEach { (label, day) ->
                FilterChip(selected = dateMillis?.toLocalDate() == day, enabled = enabled,
                    onClick = { onDateChange(day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()) },
                    label = { Text(stringResource(label)) }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { openDialog = true }, enabled = enabled) {
                Icon(painterResource(R.drawable.ic_agenda), null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(dateMillis?.toLocalDateString() ?: stringResource(R.string.choose_date))
            }
            if (dateMillis != null) TextButton(onClick = { onDateChange(null) }, enabled = enabled) {
                Text(stringResource(R.string.remove_due_date))
            }
        }
    }
    if (openDialog) {
        // Construct on opening, so quick choices and the displayed month always stay in sync.
        val picker = remember(locale) { DatePickerState(locale = locale,
            initialSelectedDateMillis = pickerDateMillis(dateMillis
                ?: today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()),
            initialDisplayMode = if (preferences.datePickerCalendar) DisplayMode.Picker else DisplayMode.Input) }
        DatePickerDialog(onDismissRequest = { openDialog = false },
            confirmButton = { TextButton(enabled = picker.selectedDateMillis != null, onClick = {
                picker.selectedDateMillis?.let { onDateChange(localDateFromPicker(it)) }
                openDialog = false
            }) { Text(stringResource(R.string.action_confirm)) } },
            dismissButton = { TextButton(onClick = { openDialog = false }) { Text(stringResource(R.string.action_cancel)) } }
        ) { DatePicker(picker, modifier = Modifier.verticalScroll(rememberScrollState())) }
    }
}
