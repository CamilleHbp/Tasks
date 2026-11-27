package studio.camille.vervetwodo.integration

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale
import java.util.UUID

/** Application rules stay outside the storage/transport helper. Dates remain calendar dates. */
object TaskRules {
    fun rollover(snapshot: WidgetSnapshot, today: LocalDate): TaskCommand? {
        val tasks = snapshot.tasks.filter {
            it.rollover == true && !it.completed && !it.deleted && !it.retracted &&
                it.dueDate.isNotEmpty() && LocalDate.parse(it.dueDate).isBefore(today)
        }.map { it.copy(dueDate = today.toString()) }
        return if (tasks.isEmpty()) null else transaction(snapshot, tasks)
    }

    fun validate(task: WidgetTask) {
        require(task.id.length in 16..128 && task.title.isNotBlank() && task.title.length <= 16000) { "invalidTitle" }
        require(task.details.length <= 100000 && task.priority in 0..4) { "invalidTask" }
        require(task.tags.size <= 100 && task.tags.distinct().size == task.tags.size) { "invalidTask" }
        require(task.checklist.size <= 500 && task.checklist.map { it.id }.distinct().size == task.checklist.size &&
            task.checklist.all { it.id.isNotBlank() && it.title.isNotBlank() && it.title.length <= 16000 }) { "invalidChecklist" }
        val date = if (task.dueDate.isEmpty()) null else runCatching { LocalDate.parse(task.dueDate) }.getOrNull()
        require(task.dueDate.isEmpty() || date != null && date.year in 1..9999) { "invalidDate" }
        require(task.dueTime.isEmpty() || date != null && runCatching {
            require(task.dueTime.matches(Regex("\\d{2}:\\d{2}")))
            LocalTime.parse(task.dueTime); ZoneId.of(task.timeZone)
        }.isSuccess) { "invalidTime" }
        task.recurrence?.let {
            require(date != null && it.frequency in setOf("daily", "weekly", "monthly", "yearly") && it.interval in 1..999 &&
                runCatching { LocalDate.parse(it.anchor) }.isSuccess) { "invalidRecurrence" }
        }
    }

    fun transaction(snapshot: WidgetSnapshot, tasks: List<WidgetTask>, tags: List<TaskTag>? = null): TaskCommand {
        require(tasks.map { it.id }.distinct().size == tasks.size)
        tasks.forEach(::validate)
        tags?.let { values ->
            require(values.map { it.id }.distinct().size == values.size &&
                values.map { it.name.trim().lowercase(Locale.ROOT) }.distinct().size == values.size &&
                values.all { it.id.length in 16..128 && it.name.isNotBlank() && it.name.length <= 100 && it.color.matches(Regex("#[a-fA-F0-9]{6}")) }) { "invalidTag" }
        }
        val now = System.currentTimeMillis()
        return TaskCommand(id = UUID.randomUUID().toString(), action = "transaction", expectedRevision = snapshot.revision,
            tasks = tasks.map { task ->
                val old = snapshot.tasks.find { it.id == task.id }
                task.copy(title = task.title.trim(), createdAt = old?.createdAt?.takeIf { it > 0 }
                    ?: snapshot.tasks.find { it.id == task.parentId }?.createdAt ?: now,
                    modifiedAt = now, generation = (old?.generation ?: 0) + 1)
            }, tags = tags)
    }

    fun nextDate(date: LocalDate, rule: Recurrence): LocalDate {
        val anchor = LocalDate.parse(rule.anchor)
        return when (rule.frequency) {
            "daily" -> date.plusDays(rule.interval.toLong())
            "weekly" -> date.plusWeeks(rule.interval.toLong())
            "monthly" -> date.withDayOfMonth(1).plusMonths(rule.interval.toLong()).let { it.withDayOfMonth(minOf(anchor.dayOfMonth, it.lengthOfMonth())) }
            "yearly" -> LocalDate.of(date.year + rule.interval, anchor.month, 1).let { it.withDayOfMonth(minOf(anchor.dayOfMonth, it.lengthOfMonth())) }
            else -> error("invalidRecurrence")
        }.also { require(it.year in 1..9999) { "invalidRecurrence" } }
    }

    fun completion(snapshot: WidgetSnapshot, ids: Set<String>, completed: Boolean): TaskCommand {
        val updates = linkedMapOf<String, WidgetTask>()
        snapshot.tasks.filter { !it.deleted && it.id in ids }.forEach { task ->
            if (task.completed == completed) return@forEach
            updates[task.id] = task.copy(completed = completed)
            if (completed && task.recurrence != null && task.dueDate.isNotEmpty()) {
                val date = nextDate(LocalDate.parse(task.dueDate), task.recurrence).toString()
                val id = UUID.nameUUIDFromBytes("${task.id}:$date".toByteArray(Charsets.UTF_8)).toString()
                // Existing successors, including explicit deletion markers, must never be overwritten.
                val existing = snapshot.tasks.find { it.id == id }
                if (existing == null || existing.retracted) updates[id] = task.copy(id = id, completed = false,
                    dueDate = date, deleted = false, retracted = false, checklist = task.checklist.map { it.copy(completed = false) }, parentId = task.id)
            } else if (!completed) {
                // An untouched generated successor can be retracted; preserve subsequent edits.
                snapshot.tasks.filter { it.parentId == task.id && it.generation == 1L && !it.deleted }.forEach {
                    updates[it.id] = it.copy(deleted = true, retracted = true)
                }
            }
        }
        return transaction(snapshot, updates.values.toList())
    }

    fun undo(before: WidgetSnapshot, command: TaskCommand, current: WidgetSnapshot): TaskCommand {
        val updates = command.tasks.mapNotNull { changed ->
            val now = current.tasks.find { it.id == changed.id } ?: return@mapNotNull null
            if (now != changed) return@mapNotNull null
            before.tasks.find { it.id == changed.id } ?: changed.copy(deleted = true, retracted = changed.parentId.isNotEmpty())
        }
        val tags = if (command.tags != null && current.tags == command.tags) before.tags else null
        require(updates.isNotEmpty() || tags != null) { "undoUnavailable" }
        return transaction(current, updates, tags)
    }
}

data class TaskGroup(val key: String, val tasks: List<WidgetTask>, val date: LocalDate? = null)

fun taskGroups(snapshot: WidgetSnapshot, status: String, sort: String, tag: String?, query: String,
               today: LocalDate = LocalDate.now(), locale: Locale = Locale.getDefault()): List<TaskGroup> {
    val names = snapshot.tags.associate { it.id to it.name }
    val deadline = compareBy<WidgetTask, String?>(nullsLast()) { it.dueDate.ifEmpty { null } }
        .thenBy { it.dueTime.ifEmpty { "99:99" } }.thenByDescending { it.priority }
    val ordering = when (sort) {
        "latest" -> compareBy<WidgetTask, String?>(nullsLast(reverseOrder())) { it.dueDate.ifEmpty { null } }.thenByDescending { it.dueTime }
        "priority" -> compareByDescending<WidgetTask> { it.priority }.then(deadline)
        "newest" -> compareByDescending { it: WidgetTask -> it.createdAt }
        "oldest" -> compareBy { it: WidgetTask -> it.createdAt }
        "alphabetical" -> compareBy(String.CASE_INSENSITIVE_ORDER) { it: WidgetTask -> it.title }
        else -> deadline
    }
    val filtered = snapshot.tasks.filter { task -> !task.deleted &&
        (status == "all" || task.completed == (status == "completed")) &&
        (tag == null || if (tag.isEmpty()) task.tags.isEmpty() else tag in task.tags) &&
        (query.isBlank() || (listOf(task.title, task.details, task.dueDate) + task.tags.mapNotNull(names::get) + task.checklist.map { it.title })
            .any { it.contains(query.trim(), ignoreCase = true) })
    }.sortedWith(compareBy<WidgetTask> { it.completed }.then(ordering).thenBy { it.id })
    if (query.isNotBlank() || tag != null) return listOf(TaskGroup("results", filtered))
    val nextWeek = today.with(TemporalAdjusters.previousOrSame(WeekFields.of(locale).firstDayOfWeek)).plusWeeks(1)
    fun key(task: WidgetTask): String {
        val day = task.dueDate.takeIf { it.isNotEmpty() }?.let(LocalDate::parse) ?: return "noDate"
        return when {
            day < today -> if (task.completed) "earlier" else "overdue"
            day == today -> "today"
            day == today.plusDays(1) -> "tomorrow"
            day == today.plusDays(2) -> "dayAfter"
            day < nextWeek -> "thisWeek"
            day < nextWeek.plusWeeks(1) -> "nextWeek"
            else -> "later"
        }
    }
    return listOf("overdue", "today", "tomorrow", "dayAfter", "thisWeek", "nextWeek", "later", "earlier", "noDate").mapNotNull { key ->
        val tasks = filtered.filter { key(it) == key }
        if (tasks.isEmpty() && key != "today") null else TaskGroup(key, tasks, if (key == "dayAfter") today.plusDays(2) else null)
    }
}

fun dueLabel(task: WidgetTask, locale: Locale): String = task.dueDate.takeIf { it.isNotEmpty() }?.let {
    LocalDate.parse(it).format(DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM).withLocale(locale)) +
        if (task.dueTime.isNotEmpty()) " · ${task.dueTime} (${task.timeZone})" else ""
}.orEmpty()
