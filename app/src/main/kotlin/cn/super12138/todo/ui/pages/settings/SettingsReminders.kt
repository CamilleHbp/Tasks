package cn.super12138.todo.ui.pages.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import cn.super12138.todo.R
import cn.super12138.todo.logic.reminders.TaskReminders
import cn.super12138.todo.ui.components.DueTimeDialog
import cn.super12138.todo.ui.pages.settings.components.SettingsItem

@Composable
fun SettingsReminders() {
    val context = LocalContext.current
    val reminderLabel = stringResource(R.string.reminder_title)
    val prefs = remember { TaskReminders.preferences(context) }
    var enabled by remember { mutableStateOf(prefs.getBoolean("enabled", true)) }
    var minutes by remember { mutableIntStateOf(prefs.getInt("minutes", 540)) }
    var open by remember { mutableStateOf(false) }
    var timePicker by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { TaskReminders.refresh(context) }
    SettingsItem(leadingIconRes = R.drawable.ic_clock, title = stringResource(R.string.reminder_title),
        description = stringResource(R.string.reminder_description), onClick = { open = true })
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(stringResource(R.string.reminder_title)) },
        text = { Column {
            Text(stringResource(R.string.reminder_description))
            Switch(enabled, { enabled = it; prefs.edit().putBoolean("enabled", it).apply(); TaskReminders.refresh(context)
                if (it && Build.VERSION.SDK_INT >= 33) permission.launch(android.Manifest.permission.POST_NOTIFICATIONS) }, modifier = Modifier.semantics { contentDescription = reminderLabel })
            TextButton(onClick = { timePicker = true }) { Text(stringResource(R.string.reminder_default_time, "%02d:%02d".format(minutes / 60, minutes % 60))) }
            TextButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }) { Text(stringResource(R.string.reminder_permissions)) }
            if (Build.VERSION.SDK_INT >= 31 && !context.getSystemService(android.app.AlarmManager::class.java).canScheduleExactAlarms()) {
                Text(stringResource(R.string.reminder_exact_hint))
                TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) }) {
                    Text(stringResource(R.string.reminder_exact))
                }
            }
        } }, confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_confirm)) } })
    if (timePicker) DueTimeDialog(minutes, { minutes = it; prefs.edit().putInt("minutes", it).apply(); TaskReminders.refresh(context); timePicker = false }, { timePicker = false })
}
