package cn.super12138.todo.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import cn.super12138.todo.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DueTimeDialog(initialMinutes: Int, onConfirm: (Int) -> Unit, onDismiss: () -> Unit, initialDial: Boolean = true) {
    var showDial by rememberSaveable { mutableStateOf(initialDial) }
    val state = rememberTimePickerState(initialHour = initialMinutes / 60,
        initialMinute = initialMinutes % 60, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.label_due_time)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            if (showDial) TimePicker(state = state) else TimeInput(state = state)
            IconButton(onClick = { showDial = !showDial }) {
                Icon(painterResource(if (showDial) R.drawable.ic_edit_task else R.drawable.ic_clock),
                    stringResource(if (showDial) R.string.editor_type_time else R.string.editor_pick_time))
            }
        } },
        confirmButton = { TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) {
            Text(stringResource(R.string.action_confirm))
        } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}
