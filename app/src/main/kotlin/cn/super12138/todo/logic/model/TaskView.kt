package cn.super12138.todo.logic.model

import cn.super12138.todo.logic.database.TaskEntity
import cn.super12138.todo.logic.database.taskTags
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

@Serializable enum class TaskViewMode { Agenda, Tags }
@Serializable enum class TaskStatusFilter { Open, Completed, All }
@Serializable enum class TaskGroupSort { DueDate, DueDateLatest, CreatedNewest, CreatedOldest, Priority, Alphabetical }

/** Shared by the app and every configurable widget; empty tags means all tags. */
@Serializable
data class TaskViewConfig(
    val mode: TaskViewMode = TaskViewMode.Agenda,
    val status: TaskStatusFilter = TaskStatusFilter.Open,
    val sort: TaskGroupSort = TaskGroupSort.DueDate,
    val individualDays: Int = 3,
    val thisWeek: Boolean = true,
    val nextWeek: Boolean = true,
    val showEmptyDays: Boolean = false,
    val tags: Set<String> = emptySet(),
    val tagOrder: List<String> = emptyList(),
    val collapsed: Set<String> = emptySet(),
    val controlsVisible: Boolean = false,
    val emphasizeGroups: Boolean = false
) {
    fun normalized() = copy(individualDays = individualDays.coerceIn(1, 7), tagOrder = tagOrder.distinct())
    fun encode(): String = Json.encodeToString(normalized())

    companion object {
        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
        fun decode(value: String?, mode: TaskViewMode = TaskViewMode.Agenda): TaskViewConfig =
            value?.let { runCatching { json.decodeFromString<TaskViewConfig>(it).normalized() }.getOrNull() }
                ?: TaskViewConfig(mode = mode)
    }
}

enum class TaskGroupKind { Overdue, Day, ThisWeek, NextWeek, Later, OtherDates, Earlier, NoDate, Tag, Untagged, Search }

data class TaskGroup(
    val key: String,
    val kind: TaskGroupKind,
    val tasks: List<TaskEntity>,
    val date: LocalDate? = null,
    val endDate: LocalDate? = null,
    val tag: String? = null
)

fun TaskEntity.dueDay(zone: ZoneId = ZoneId.systemDefault()): LocalDate? =
    dueDateMillis?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }

fun taskComparator(sort: TaskGroupSort, zone: ZoneId = ZoneId.systemDefault()): Comparator<TaskEntity> {
    val date = compareBy<TaskEntity, LocalDate?>(nullsLast()) { it.dueDay(zone) }
    val time = compareBy<TaskEntity> { it.dueTimeMinutes ?: Int.MAX_VALUE }
    val priority = compareByDescending<TaskEntity> { it.priority }
    // Legacy imports have no creation timestamp. Their insertion IDs are a stable fallback.
    val created = compareBy<TaskEntity> { it.createdAtMillis != null }
        .thenBy { it.createdAtMillis ?: it.id.toLong() }
    val byDeadline = date.then(time).then(priority).then(created)
    val order = when (sort) {
        TaskGroupSort.DueDate -> byDeadline
        TaskGroupSort.DueDateLatest -> compareBy<TaskEntity, LocalDate?>(nullsLast(reverseOrder())) { it.dueDay(zone) }
            .thenByDescending { it.dueTimeMinutes ?: -1 }.then(priority).then(created)
        TaskGroupSort.Priority -> priority.then(date).then(time).then(created)
        TaskGroupSort.CreatedNewest -> created.reversed().then(date).then(time).then(priority)
        TaskGroupSort.CreatedOldest -> created.then(date).then(time).then(priority)
        TaskGroupSort.Alphabetical -> compareBy<TaskEntity, String>(String.CASE_INSENSITIVE_ORDER) { it.content }.then(byDeadline)
    }
    return compareBy<TaskEntity> { it.isCompleted }.then(order).thenBy { it.id }
}

/** Every task has exactly one Agenda home. Tags intentionally repeat task identities. */
fun taskGroups(
    tasks: List<TaskEntity>,
    configuration: TaskViewConfig,
    today: LocalDate = LocalDate.now(),
    zone: ZoneId = ZoneId.systemDefault(),
    firstDayOfWeek: DayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek,
    query: String = ""
): List<TaskGroup> {
    val config = configuration.normalized()
    val filtered = tasks.distinctBy { it.id }.filter { task ->
        (config.status == TaskStatusFilter.All || task.isCompleted == (config.status == TaskStatusFilter.Completed)) &&
            (config.tags.isEmpty() || task.taskTags.any { it in config.tags } ||
                (task.taskTags.isEmpty() && "" in config.tags)) &&
            (query.isBlank() || listOf(task.content, task.details, task.taskTags.joinToString(" "), task.dueDay(zone)?.toString().orEmpty())
                .any { it.contains(query.trim(), ignoreCase = true) })
    }.sortedWith(taskComparator(config.sort, zone))
    if (query.isNotBlank()) return if (filtered.isEmpty()) emptyList()
        else listOf(TaskGroup("search", TaskGroupKind.Search, filtered))
    if (config.mode == TaskViewMode.Tags) {
        val tags = filtered.flatMap { it.taskTags }.distinct()
            .filter { config.tags.isEmpty() || it in config.tags }
            .sortedWith(compareBy<String> { config.tagOrder.indexOf(it).let { index -> if (index < 0) Int.MAX_VALUE else index } }
                .then(String.CASE_INSENSITIVE_ORDER))
        return buildList {
            tags.forEach { tag -> add(TaskGroup("tag:$tag", TaskGroupKind.Tag, filtered.filter { tag in it.taskTags }, tag = tag)) }
            val untagged = filtered.filter { it.taskTags.isEmpty() }
            if (untagged.isNotEmpty()) add(TaskGroup("untagged", TaskGroupKind.Untagged, untagged))
        }
    }
    val weekStart = today.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
    val nextWeek = weekStart.plusWeeks(1)
    val afterNextWeek = nextWeek.plusWeeks(1)
    val dailyEnd = today.plusDays(config.individualDays.toLong())
    val groups = linkedMapOf<String, TaskGroup>()
    fun add(key: String, kind: TaskGroupKind, date: LocalDate? = null, end: LocalDate? = null, predicate: (TaskEntity) -> Boolean) {
        val matching = filtered.filter(predicate)
        if (matching.isNotEmpty() || (kind == TaskGroupKind.Day && (date == today || config.showEmptyDays))) {
            groups[key] = TaskGroup(key, kind, matching, date, end)
        }
    }
    add("overdue", TaskGroupKind.Overdue) { !it.isCompleted && it.dueDay(zone)?.isBefore(today) == true }
    repeat(config.individualDays) { offset ->
        val day = today.plusDays(offset.toLong())
        add("day:$day", TaskGroupKind.Day, day) { it.dueDay(zone) == day }
    }
    if (config.thisWeek && dailyEnd < nextWeek) add("this-week", TaskGroupKind.ThisWeek, dailyEnd, nextWeek.minusDays(1)) {
        it.dueDay(zone)?.let { date -> date >= dailyEnd && date < nextWeek } == true
    }
    if (config.nextWeek) add("next-week", TaskGroupKind.NextWeek, maxOf(dailyEnd, nextWeek), afterNextWeek.minusDays(1)) {
        it.dueDay(zone)?.let { date -> date >= maxOf(dailyEnd, nextWeek) && date < afterNextWeek } == true
    }
    val assigned = groups.values.flatMap { it.tasks }.map { it.id }.toSet()
    val other = !config.thisWeek || !config.nextWeek
    add(if (other) "other-dates" else "later", if (other) TaskGroupKind.OtherDates else TaskGroupKind.Later) {
        it.id !in assigned && it.dueDay(zone)?.let { date -> date >= today } == true
    }
    add("earlier", TaskGroupKind.Earlier) { it.isCompleted && it.dueDay(zone)?.isBefore(today) == true }
    add("no-date", TaskGroupKind.NoDate) { it.dueDateMillis == null }
    return groups.values.toList()
}
