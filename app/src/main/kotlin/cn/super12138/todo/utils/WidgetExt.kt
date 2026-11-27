package cn.super12138.todo.utils

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.text.FontWeight
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import cn.super12138.todo.logic.model.Priority
import cn.super12138.todo.ui.widget.taskWidgetProviders
import cn.super12138.todo.ui.widget.upcoming.UpcomingTaskWidgetReceiver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

suspend fun updateTaskWidgets(context: Context) {
    cn.super12138.todo.ui.widget.refreshWidgetLanguage(context)
    val manager = AppWidgetManager.getInstance(context)
    val glanceManager = GlanceAppWidgetManager(context)
    supervisorScope {
        taskWidgetProviders.forEach { (receiver, createWidget) ->
            // Android's instance list survives updates. Glance's cached class-name mapping
            // may still contain names from an older, differently obfuscated release.
            val ids = manager.getAppWidgetIds(ComponentName(context, receiver))
            if (ids.isEmpty()) return@forEach
            val widget = createWidget()
            ids.forEach { id ->
                launch {
                    try {
                        widget.update(context, glanceManager.getGlanceIdBy(id))
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: Exception) {
                        Log.w("TaskWidgets", "Could not refresh ${receiver.simpleName} $id", exception)
                    }
                }
            }
        }
    }
}

suspend fun updateTagWidgets(context: Context, old: String?, replacement: String?) {
    if (old != null && old != replacement) {
        org.koin.core.context.GlobalContext.get().get<cn.super12138.todo.logic.TaskViewsRepository>().replaceTag(old, replacement)
        val manager = GlanceAppWidgetManager(context)
        AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, UpcomingTaskWidgetReceiver::class.java))
            .map(manager::getGlanceIdBy).forEach { id ->
            androidx.glance.appwidget.state.updateAppWidgetState(context, id) { preferences ->
                cn.super12138.todo.ui.widget.upcoming.UpcomingWidgetPreferences.replaceTag(preferences, old, replacement)
            }
        }
    }
    updateTaskWidgets(context)
}

object GlanceTypography {
    val defaultColor: ColorProvider
        @Composable get() = GlanceTheme.colors.onSurface
    val titleLarge: TextStyle
        @Composable get() = TextStyle(
            color = defaultColor,
            fontWeight = FontWeight.Normal,
            fontSize = 22.sp
        )
    val titleMedium: TextStyle
        @Composable get() = TextStyle(
            color = defaultColor,
            fontWeight = FontWeight.Medium,
            fontSize = 16.sp
        )
    val labelLarge: TextStyle
        @Composable get() = TextStyle(
            color = defaultColor,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
        )
    val labelMedium: TextStyle
        @Composable get() = TextStyle(
            color = defaultColor,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp
        )
}

data class FixedColorProvider(val color: Color) : ColorProvider {
    override fun getColor(context: Context): Color = color
}

fun Color.toColorProvider() = FixedColorProvider(this)

@Stable
@Composable
fun Priority.glanceContainerColor(): ColorProvider =
    when (this) {
        Priority.NotUrgent -> GlanceTheme.colors.onSurfaceVariant
        Priority.NotImportant -> GlanceTheme.colors.onSurfaceVariant
        Priority.Default -> GlanceTheme.colors.secondary
        Priority.Important -> GlanceTheme.colors.tertiary
        Priority.Urgent -> GlanceTheme.colors.error
    }
