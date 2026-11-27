package cn.super12138.todo.ui.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/** Rebind existing widget layouts and actions after an APK update, preserving their settings. */
class TaskWidgetUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val manager = AppWidgetManager.getInstance(context)
        taskWidgetProviders.keys.forEach { receiver ->
            val component = ComponentName(context, receiver)
            val ids = manager.getAppWidgetIds(component)
            if (ids.isNotEmpty()) {
                // Let Glance's receiver manage goAsync and rebuild its provider registry too.
                context.sendBroadcast(Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .setComponent(component)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids))
            }
        }
    }
}
