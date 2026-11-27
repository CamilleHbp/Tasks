package cn.super12138.todo.ui.pages.collections

import android.content.Context
import cn.super12138.todo.R
import cn.super12138.todo.logic.database.TaskEntity
import cn.super12138.todo.logic.database.taskTags
import cn.super12138.todo.logic.formatDueTime
import cn.super12138.todo.logic.model.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter

fun TaskStatusFilter.labelRes() = when (this) {
    TaskStatusFilter.Open -> R.string.view_open
    TaskStatusFilter.Completed -> R.string.view_completed
    TaskStatusFilter.All -> R.string.view_all
}

fun TaskGroup.title(context: Context, today: LocalDate): String = when (kind) {
    TaskGroupKind.Day -> when (date) {
        today -> context.getString(R.string.time_today)
        today.plusDays(1) -> context.getString(R.string.time_tomorrow)
        else -> requireNotNull(date).format(DateTimeFormatter.ofPattern("EEE d MMM", context.resources.configuration.locales[0]))
    }
    TaskGroupKind.Tag -> tag.orEmpty()
    else -> context.getString(when (kind) {
        TaskGroupKind.Overdue -> R.string.view_overdue
        TaskGroupKind.ThisWeek -> R.string.view_this_week
        TaskGroupKind.NextWeek -> R.string.widget_next_week
        TaskGroupKind.Later -> R.string.widget_later
        TaskGroupKind.OtherDates -> R.string.view_other_dates
        TaskGroupKind.Earlier -> R.string.view_earlier
        TaskGroupKind.NoDate -> R.string.view_no_date
        TaskGroupKind.Untagged -> R.string.widget_no_tag
        else -> R.string.view_search_results
    })
}

fun TaskGroup.subtitle(context: Context, today: LocalDate): String? {
    val format = DateTimeFormatter.ofPattern("EEE d MMM", context.resources.configuration.locales[0])
    return when {
        kind == TaskGroupKind.Day && (date == today || date == today.plusDays(1)) -> date.format(format)
        kind in setOf(TaskGroupKind.ThisWeek, TaskGroupKind.NextWeek) && date != null ->
            date.format(DateTimeFormatter.ofPattern("d MMM", context.resources.configuration.locales[0])) +
                " – " + endDate!!.format(DateTimeFormatter.ofPattern("d MMM", context.resources.configuration.locales[0]))
        else -> null
    }
}

fun taskMetadata(context: Context, task: TaskEntity, group: TaskGroup?, today: LocalDate): String = buildList {
    val due = task.dueDay()
    if (group?.kind != TaskGroupKind.Day) {
        add(when (due) {
            null -> context.getString(R.string.view_no_date)
            today -> context.getString(R.string.time_today)
            today.plusDays(1) -> context.getString(R.string.time_tomorrow)
            else -> due.format(DateTimeFormatter.ofPattern("EEE d MMM", context.resources.configuration.locales[0]))
        })
    }
    task.dueTimeMinutes?.let { add(formatDueTime(it)) }
    val priority = Priority.fromFloat(task.priority)
    if (priority != Priority.Default) add(context.getString(priority.nameRes))
    if (task.subtasks.isNotEmpty()) add(context.getString(R.string.view_checklist_progress,
        task.subtasks.count { it.isCompleted }, task.subtasks.size))
}.joinToString(" · ")


fun TaskGroupSort.shortLabelRes() = when (this) {
    TaskGroupSort.DueDate -> R.string.view_sort_soonest
    TaskGroupSort.DueDateLatest -> R.string.view_sort_latest
    TaskGroupSort.CreatedNewest -> R.string.view_sort_newest
    TaskGroupSort.CreatedOldest -> R.string.view_sort_oldest
    TaskGroupSort.Priority -> R.string.view_sort_priority
    TaskGroupSort.Alphabetical -> R.string.view_sort_alphabetical
}
