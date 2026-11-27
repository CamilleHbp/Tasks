package cn.super12138.todo.ui.widget.upcoming

import cn.super12138.todo.ui.widget.VerveDoWidgetTheme
import cn.super12138.todo.ui.widget.LocalWidgetBackground
import cn.super12138.todo.ui.widget.LocalWidgetBackgroundAlpha

import cn.super12138.todo.ui.widget.LocalizedWidgetContent

import cn.super12138.todo.ui.widget.components.GlanceCategoryRing
import cn.super12138.todo.ui.widget.components.GlanceCategoryIcons
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.datastore.preferences.core.Preferences
import androidx.glance.*
import androidx.glance.action.*
import androidx.glance.appwidget.*
import androidx.glance.appwidget.action.*
import androidx.glance.appwidget.components.SquareIconButton
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.layout.*
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.*
import androidx.glance.unit.ColorProvider
import cn.super12138.todo.R
import cn.super12138.todo.logic.*
import cn.super12138.todo.logic.database.TaskEntity
import cn.super12138.todo.logic.database.taskTags
import cn.super12138.todo.logic.model.*
import cn.super12138.todo.ui.activities.MainActivity
import cn.super12138.todo.ui.pages.collections.shortLabelRes
import cn.super12138.todo.ui.pages.collections.labelRes
import cn.super12138.todo.ui.pages.collections.title
import cn.super12138.todo.ui.pages.collections.subtitle
import cn.super12138.todo.ui.pages.collections.taskMetadata
import cn.super12138.todo.ui.widget.components.TagColorStrokes
import cn.super12138.todo.utils.GlanceTypography
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.time.LocalDate

class UpcomingTaskWidget : GlanceAppWidget(), KoinComponent {
    override val stateDefinition = PreferencesGlanceStateDefinition
    override val sizeMode = SizeMode.Exact
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val widgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val tasks = get<TaskRepository>().getAllTasks()
        val colors = get<TagRepository>().colors
        val initialTasks = tasks.first()
        val initialColors = colors.first()
        val appearanceFlow = get<cn.super12138.todo.logic.SettingsRepository>().appearanceFlow
        val initialAppearance = appearanceFlow.first()
        val schedulingFlow = get<cn.super12138.todo.logic.SettingsRepository>().schedulingPreferencesFlow
        val initialScheduling = schedulingFlow.first()
        provideContent {
            LocalizedWidgetContent {
                val scheduling by schedulingFlow.collectAsState(initialScheduling)
                val appearance by appearanceFlow.collectAsState(initialAppearance)
                val allTasks by tasks.collectAsState(initialTasks)
                val tagColors by colors.collectAsState(initialColors)
                val preferences = currentState<Preferences>()
                val config = UpcomingWidgetPreferences.configuration(preferences)
                val today = LocalDate.now()
                val firstDayOfWeek = scheduling.weekStart(
                    LocalContext.current.resources.configuration.locales[0]
                )
                val undoUntil = preferences[UpcomingWidgetPreferences.undoUntilKey] ?: 0L
                LaunchedEffect(undoUntil) {
                    if (undoUntil > 0L) {
                        delay((undoUntil - System.currentTimeMillis()).coerceAtLeast(0L))
                        updateAppWidgetState(context, id) {
                            if (it[UpcomingWidgetPreferences.undoUntilKey] == undoUntil) UpcomingWidgetPreferences.clearUndo(it)
                        }
                        this@UpcomingTaskWidget.update(context, id)
                    }
                }
                val undoId = UpcomingWidgetPreferences.undoTaskId(preferences)
                val undoTitle = preferences[UpcomingWidgetPreferences.undoTitleKey]
                    ?.takeIf { allTasks.any { task -> task.id == undoId && task.isCompleted } }
                VerveDoWidgetTheme(appearance) {
                    GroupedWidgetContent(taskGroups(allTasks, config, today, firstDayOfWeek = firstDayOfWeek), config, today, tagColors,
                        widgetId, undoTitle)
                }
            }
        }
    }
}

private fun controlAction(command: String, value: String = ""): Action =
    actionRunCallback<UpcomingControlsAction>(actionParametersOf(
        UpcomingControlsAction.commandKey to command, UpcomingControlsAction.valueKey to value))

@Composable
private fun GroupedWidgetContent(groups: List<TaskGroup>, config: TaskViewConfig, today: LocalDate,
    tagColors: Map<String, Int>, widgetId: Int, undoTitle: String?) {
    val context = LocalContext.current
    val size = LocalSize.current
    val open = actionStartActivity(Intent(context, MainActivity::class.java).apply {
        data = ("vervetwodo://widget/view/" + widgetId).toUri()
        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        putExtra(MainActivity.EXTRA_WIDGET_CONFIGURATION, config.encode())
    })
    val configure = actionStartActivity(Intent(context, UpcomingWidgetConfigurationActivity::class.java).apply {
        data = ("vervetwodo://widget/configuration/" + widgetId).toUri()
        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
    })
    val add = actionStartActivity(Intent(context, WidgetTaskCreationActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
        putExtra(WidgetTaskCreationActivity.EXTRA_CATEGORY, config.tags.singleOrNull().orEmpty())
    })
    Box(modifier = GlanceModifier.fillMaxSize().appWidgetBackground()
        .background(LocalWidgetBackground.current).cornerRadius(24.dp)
        .padding(horizontal = if (size.width < 280.dp) 8.dp else 12.dp, vertical = 4.dp)) {
        Column(GlanceModifier.fillMaxSize()) {
            Row(GlanceModifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(GlanceModifier.defaultWeight().fillMaxHeight().clickable(open), verticalAlignment = Alignment.CenterVertically) {
                    Text(context.getString(if (config.mode == TaskViewMode.Agenda) R.string.view_agenda else R.string.view_tags),
                        style = GlanceTypography.titleMedium, maxLines = 1)
                }
                WidgetIcon(R.drawable.ic_tune, context.getString(R.string.view_options), configure)
                WidgetIcon(if (config.controlsVisible) R.drawable.ic_widget_collapse else R.drawable.ic_widget_expand,
                    context.getString(if (config.controlsVisible) R.string.widget_hide_controls else R.string.widget_show_controls), controlAction("controls"))
                // Keep Add outside the scrolling list so it cannot intercept group-header taps.
                SquareIconButton(imageProvider = ImageProvider(R.drawable.ic_add), contentDescription = context.getString(R.string.action_add_task),
                    onClick = add, modifier = GlanceModifier.size(48.dp), backgroundColor = GlanceTheme.colors.primary, contentColor = GlanceTheme.colors.onPrimary)
            }
            if (config.controlsVisible) {
                Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(context.getString(config.status.labelRes()), style = GlanceTypography.labelLarge,
                        modifier = GlanceModifier.defaultWeight().height(48.dp).padding(vertical = 14.dp).clickable(controlAction("status")))
                    Text(context.getString(config.sort.shortLabelRes()), style = GlanceTypography.labelLarge, maxLines = 1,
                        modifier = GlanceModifier.defaultWeight().height(48.dp).padding(vertical = 14.dp).clickable(configure))
                }
                val tags = if (config.tags.isEmpty()) context.getString(R.string.widget_all_tags)
                    else config.tags.joinToString(" + ") { it.ifEmpty { context.getString(R.string.widget_no_tag) } }
                Text(context.getString(R.string.view_scope, context.getString(config.status.labelRes()), tags, context.getString(config.sort.shortLabelRes())),
                    style = GlanceTypography.labelMedium.copy(color = GlanceTheme.colors.onSurfaceVariant),
                    maxLines = 1, modifier = GlanceModifier.fillMaxWidth().padding(bottom = 4.dp))
            }
            LazyColumn(GlanceModifier.fillMaxWidth().defaultWeight()) {
                if (groups.isEmpty()) item {
                    Text(context.getString(R.string.view_empty), style = GlanceTypography.labelLarge,
                        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 20.dp).clickable(configure))
                }
                groups.forEach { group ->
                    val collapsed = group.key in config.collapsed
                    item { GroupHeader(group, today, tagColors, collapsed, config.emphasizeGroups) }
                    if (!collapsed) {
                        if (group.tasks.isEmpty()) item {
                            Text(context.getString(if (group.date == today) R.string.view_today_empty else R.string.view_day_empty),
                                style = GlanceTypography.labelMedium.copy(color = GlanceTheme.colors.onSurfaceVariant),
                                modifier = GlanceModifier.fillMaxWidth().tinted(group.tint(tagColors), 0.04f).padding(12.dp))
                        }
                        // Positional IDs allow a single task to appear in several tag groups.
                        items(group.tasks) { task -> GroupedTaskRow(task, group, today, tagColors, config, widgetId,
                            titleLines = if (size.width >= 240.dp && size.height >= 250.dp) 2 else 1) }
                    }
                }
            }
            if (undoTitle != null) Row(GlanceModifier.fillMaxWidth().height(48.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(undoTitle, style = GlanceTypography.labelMedium, maxLines = 1, modifier = GlanceModifier.defaultWeight())
                Text(context.getString(R.string.widget_undo), style = GlanceTypography.labelLarge.copy(color = GlanceTheme.colors.primary),
                    modifier = GlanceModifier.height(48.dp).padding(horizontal = 8.dp, vertical = 14.dp)
                        .clickable(actionRunCallback<UndoUpcomingTaskAction>()))
            }
        }
    }
}

@Composable
private fun TaskGroup.tint(colors: Map<String, Int>): ColorProvider = when (kind) {
    TaskGroupKind.Overdue -> GlanceTheme.colors.error
    TaskGroupKind.Tag -> ColorProvider(Color(assignTagColors(listOf(requireNotNull(tag)), colors).getValue(tag)))
    TaskGroupKind.Day -> GlanceTheme.colors.primary
    TaskGroupKind.ThisWeek, TaskGroupKind.NextWeek -> GlanceTheme.colors.secondary
    else -> GlanceTheme.colors.onSurfaceVariant
}

@Composable
private fun GlanceModifier.tinted(color: ColorProvider, alpha: Float) =
    background(ImageProvider(R.drawable.widget_section_background), colorFilter = ColorFilter.tint(color), alpha = alpha * LocalWidgetBackgroundAlpha.current)

@Composable
private fun GroupHeader(group: TaskGroup, today: LocalDate, colors: Map<String, Int>, collapsed: Boolean, emphasized: Boolean) {
    val context = LocalContext.current
    val narrow = LocalSize.current.width < 280.dp
    val title = group.title(context, today)
    val subtitle = group.subtitle(context, today)
    val tint = group.tint(colors)
    Column(GlanceModifier.fillMaxWidth().padding(top = if (collapsed) 8.dp else 0.dp)) {
        Spacer(GlanceModifier.fillMaxWidth().height(1.dp).tinted(GlanceTheme.colors.onSurface, 0.12f))
        Row(GlanceModifier.fillMaxWidth().tinted(tint, if (emphasized) 0.09f else 0f).clickable(controlAction("group", group.key))
            .semantics { contentDescription = context.getString(R.string.view_section_state, title, group.tasks.size,
                context.getString(if (collapsed) R.string.view_collapsed else R.string.view_expanded)) }
            .padding(horizontal = if (narrow) 4.dp else 8.dp, vertical = if (collapsed) 8.dp else 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(GlanceModifier.width(0.dp).height(if (collapsed) 32.dp else 20.dp))
            if (group.kind == TaskGroupKind.Tag || group.kind == TaskGroupKind.Overdue) {
                Spacer(GlanceModifier.width(3.dp).height(20.dp).background(tint))
                Spacer(GlanceModifier.width(8.dp))
            }
            val titleStyle = GlanceTypography.labelLarge.copy(
                color = if (group.kind == TaskGroupKind.Overdue) GlanceTheme.colors.error else GlanceTheme.colors.onSurface)
            if (collapsed) {
                Column(GlanceModifier.defaultWeight()) {
                    Text(title, style = titleStyle, maxLines = 2)
                    if (subtitle != null) Text(subtitle, style = GlanceTypography.labelMedium.copy(color = GlanceTheme.colors.onSurfaceVariant), maxLines = 1)
                }
            } else {
                Row(GlanceModifier.defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = titleStyle, maxLines = 1)
                    if (subtitle != null) {
                        Spacer(GlanceModifier.width(6.dp))
                        Text(subtitle, style = GlanceTypography.labelMedium.copy(color = GlanceTheme.colors.onSurfaceVariant),
                            maxLines = 1, modifier = GlanceModifier.defaultWeight())
                    }
                }
            }
            Spacer(GlanceModifier.width(8.dp))
            Text(group.tasks.size.toString(), style = GlanceTypography.labelMedium.copy(color = GlanceTheme.colors.onSurfaceVariant))
            Spacer(GlanceModifier.width(12.dp))
            Image(ImageProvider(if (collapsed) R.drawable.ic_chevron_right else R.drawable.ic_widget_expand), null,
                modifier = GlanceModifier.size(16.dp), colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant))
        }
    }
}

@Composable
private fun WidgetIcon(icon: Int, description: String, action: Action) {
    Box(GlanceModifier.size(48.dp).clickable(action), contentAlignment = Alignment.Center) {
        Image(ImageProvider(icon), description, modifier = GlanceModifier.size(20.dp), colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant))
    }
}

@Composable
private fun GroupedTaskRow(task: TaskEntity, group: TaskGroup, today: LocalDate, colors: Map<String, Int>,
    configuration: TaskViewConfig, widgetId: Int, titleLines: Int) {
    val context = LocalContext.current
    val parameters = actionParametersOf(CompleteUpcomingTaskAction.taskIdKey to task.id)
    val toggle = if (task.isCompleted) actionRunCallback<RestoreUpcomingTaskAction>(parameters) else actionRunCallback<CompleteUpcomingTaskAction>(parameters)
    val open = actionStartActivity(Intent(context, MainActivity::class.java).apply {
        data = ("vervetwodo://widget/" + widgetId + "/task/" + task.id).toUri()
        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        putExtra(MainActivity.EXTRA_WIDGET_TASK_ID, task.id)
        putExtra(MainActivity.EXTRA_WIDGET_CONFIGURATION, configuration.encode())
    })
    Row(GlanceModifier.fillMaxWidth().tinted(group.tint(colors), 0.045f).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(GlanceModifier.size(48.dp).clickable(toggle), contentAlignment = Alignment.Center) {
            if (task.isCompleted) Image(ImageProvider(R.drawable.ic_check_circle),
                context.getString(R.string.widget_restore_task, task.content), modifier = GlanceModifier.size(24.dp),
                colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant))
            else GlanceCategoryRing(task.taskTags.map { cn.super12138.todo.logic.assignTagColors(task.taskTags, colors).getValue(it) },
                context.getString(R.string.widget_complete_task, task.content))
        }
        Column(GlanceModifier.defaultWeight().clickable(open).padding(vertical = 10.dp)) {
            Text(task.content, style = GlanceTypography.titleMedium.copy(fontWeight = FontWeight.Normal,
                color = if (task.isCompleted) GlanceTheme.colors.onSurfaceVariant else GlanceTheme.colors.onSurface,
                textDecoration = if (task.isCompleted) TextDecoration.LineThrough else TextDecoration.None), maxLines = titleLines)
            val metadata = taskMetadata(context, task, group, today)
            if (metadata.isNotEmpty()) Text(metadata, style = GlanceTypography.labelMedium.copy(fontWeight = FontWeight.Normal,
                color = if (!task.isCompleted && task.dueDay()?.isBefore(today) == true) GlanceTheme.colors.error else GlanceTheme.colors.onSurfaceVariant), maxLines = 2)
        }
        Box(GlanceModifier.padding(start = 8.dp).clickable(open)) { GlanceCategoryIcons(task.taskTags) }
    }
}
