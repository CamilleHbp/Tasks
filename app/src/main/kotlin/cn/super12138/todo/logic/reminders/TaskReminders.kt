package cn.super12138.todo.logic.reminders

import android.app.*
import android.content.*
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import cn.super12138.todo.R
import cn.super12138.todo.logic.TaskRepository
import cn.super12138.todo.ui.activities.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.context.GlobalContext

object TaskReminders {
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    fun preferences(context: Context) = context.getSharedPreferences("task_reminders", Context.MODE_PRIVATE)
    fun refresh(context: Context) { scope.launch { reconcile(context) } }
    private fun alarm(context: Context, id: Int, time: Long = 0) = PendingIntent.getBroadcast(context, id,
        Intent(context, ReminderReceiver::class.java).setAction("task_reminder").putExtra("id", id).putExtra("time", time),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    suspend fun reconcile(context: Context) = lock.withLock {
        val prefs = preferences(context)
        val manager = context.getSystemService(AlarmManager::class.java)
        val tasks = GlobalContext.get().get<TaskRepository>().getAllTasks().first()
        val now = System.currentTimeMillis()
        val desired = if (prefs.getBoolean("enabled", true)) tasks.mapNotNull { task ->
            val at = runCatching { task.reminderTime(prefs.getInt("minutes", 540)) }.getOrNull()
            if (at != null && at > now) task.id to at else null
        }.toMap() else emptyMap()
        val old = prefs.getStringSet("scheduled", emptySet()).orEmpty()
        for (id in old.mapNotNull(String::toIntOrNull)) if (id !in desired) manager.cancel(alarm(context, id))
        for ((id, at) in desired) {
            val pending = alarm(context, id, at)
            if (Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()) {
                try { manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending) }
                catch (_: SecurityException) { manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending) }
            } else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
        prefs.edit().putStringSet("scheduled", desired.keys.map(Int::toString).toSet()).apply()
    }

    suspend fun deliver(context: Context, id: Int, expected: Long) = lock.withLock {
        val prefs = preferences(context)
        if (!prefs.getBoolean("enabled", true)) return@withLock
        val task = GlobalContext.get().get<TaskRepository>().getAllTasks().first().find { it.id == id } ?: return@withLock
        if (runCatching { task.reminderTime(prefs.getInt("minutes", 540)) }.getOrNull() != expected ||
            expected > System.currentTimeMillis() || prefs.getLong("delivered_$id", -1) == expected) return@withLock
        val localized = ContextCompat.getContextForLanguage(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("tasks", localized.getString(R.string.reminder_title), NotificationManager.IMPORTANCE_HIGH))
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return@withLock
        val open = PendingIntent.getActivity(context, id, Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_WIDGET_TASK_ID, id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try {
            manager.notify(id, NotificationCompat.Builder(localized, "tasks").setSmallIcon(R.drawable.ic_clock)
                .setContentTitle(task.content).setContentText(localized.getString(R.string.reminder_body))
                .setContentIntent(open).setAutoCancel(true).build())
            prefs.edit().putLong("delivered_$id", expected).apply()
        } catch (_: SecurityException) { /* Permission may be revoked during delivery. */ }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (intent.action == "task_reminder") TaskReminders.deliver(context, intent.getIntExtra("id", -1), intent.getLongExtra("time", 0))
                else TaskReminders.reconcile(context)
            } catch (error: Exception) { android.util.Log.e("TaskReminders", "Reminder update failed", error) }
            finally { pending.finish() }
        }
    }
}
