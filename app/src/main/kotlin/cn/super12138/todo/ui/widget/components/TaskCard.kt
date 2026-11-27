package cn.super12138.todo.ui.widget.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.Action
import androidx.glance.action.action
import androidx.glance.action.clickable
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.wrapContentHeight
import androidx.glance.text.Text
import androidx.glance.unit.ColorProvider
import cn.super12138.todo.R
import cn.super12138.todo.logic.model.Priority
import cn.super12138.todo.ui.VerveDoDefaults
import cn.super12138.todo.utils.GlanceTypography
import cn.super12138.todo.utils.toColorProvider
import cn.super12138.todo.utils.toLocalDateString
import cn.super12138.todo.utils.toRelativeTimeString

@Composable
fun GlanceTaskCard(
    content: String,
    isCompleted: Boolean,
    priority: Priority,
    dueDateMillis: Long?,
    tags: List<String> = emptyList(),
    tagColors: Map<String, Int> = emptyMap(),
    dueTimeMinutes: Int? = null,
    modifier: GlanceModifier = GlanceModifier,
    showDueDate: Boolean = true,
    dueDateLabel: String? = null,
    onCheckedAction: Action? = null,
    onChecked: () -> Unit = {}
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .padding(vertical = VerveDoDefaults.contentPadding / 2)
    ) {
        if (!isCompleted) {
            Box(GlanceModifier.size(48.dp).clickable(onCheckedAction ?: action(block = onChecked)), contentAlignment = Alignment.Center) {
                val colors = cn.super12138.todo.logic.assignTagColors(tags, tagColors)
                GlanceCategoryRing(tags.map { colors.getValue(it) }, LocalContext.current.getString(R.string.widget_complete_task, content))
            }
            Spacer(GlanceModifier.size(8.dp))
        }
        if (dueTimeMinutes != null) {
            Text(cn.super12138.todo.logic.formatDueTime(dueTimeMinutes),
                style = GlanceTypography.labelMedium.copy(color = GlanceTheme.colors.onSurfaceVariant),
                modifier = GlanceModifier.padding(end = 8.dp))
        }
        Column(
            verticalAlignment = Alignment.CenterVertically,
            modifier = GlanceModifier.defaultWeight()
        ) {
            Text(
                text = content,
                style = GlanceTypography.titleMedium,
                maxLines = 1
            )
            if (!isCompleted) {
                if (showDueDate) {
                    dueDateMillis?.let {
                        DueDatePresenter(
                            dueDateMillis = it,
                            label = dueDateLabel,
                            modifier = GlanceModifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
        if (!isCompleted && priority != Priority.Default) {
            GlancePriorityIcon(priority, modifier = GlanceModifier.padding(start = 8.dp))
        }
        Box(GlanceModifier.padding(start = 8.dp)) { GlanceCategoryIcons(tags) }
    }
}

@Composable
fun DueDatePresenter(
    dueDateMillis: Long?,
    modifier: GlanceModifier = GlanceModifier,
    label: String? = null
) {
    val context = LocalContext.current

    val dueDate = remember(dueDateMillis, context) { dueDateMillis.toLocalDateString() }
    val relativeDueDate = remember(dueDateMillis, context) { dueDateMillis.toRelativeTimeString(context) }
    val combinedText = remember(dueDateMillis, context) {
        context.getString(R.string.label_slot_date_with_relative, dueDate, relativeDueDate)
    }

    Text(
        text = label ?: combinedText,
        style = GlanceTypography.labelMedium.copy(
            color = GlanceTheme.colors.onSurfaceVariant
        ),
        maxLines = 1,
        modifier = modifier
    )
}

@Composable
fun CheckButton(
    modifier: GlanceModifier = GlanceModifier,
    contentColor: ColorProvider = Color.White.toColorProvider(),
    backgroundColor: Color = VerveDoDefaults.Colors.Green,
    onCheckedAction: Action? = null,
    onChecked: () -> Unit = {}
) {
    val context = LocalContext.current
    val description = remember(context) { context.getString(R.string.tip_mark_completed) }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .background(backgroundColor)
            .size(48.dp)
            .cornerRadius(100.dp)
            .padding(VerveDoDefaults.contentPadding / 2)
            .clickable(onCheckedAction ?: action(block = onChecked))
    ) {
        Image(
            provider = ImageProvider(R.drawable.ic_check),
            contentDescription = description,
            colorFilter = ColorFilter.tint(contentColor)
        )
    }
}
