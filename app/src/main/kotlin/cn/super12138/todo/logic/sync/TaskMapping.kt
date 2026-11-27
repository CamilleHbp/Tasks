package cn.super12138.todo.logic.sync

import cn.super12138.todo.logic.database.*
import cn.super12138.todo.logic.TaskRecurrence
import cn.super12138.todo.logic.RepeatFrequency
import studio.camille.vervetwodo.integration.*
import java.time.*
import java.util.UUID

fun normalizeLocalTask(task: TaskEntity, previous: TaskEntity?): TaskEntity {
    val date = when {
        task.dueDateMillis == null -> ""
        previous != null && previous.dueDateMillis == task.dueDateMillis && previous.calendarDate.isNotEmpty() -> previous.calendarDate
        previous == null && task.calendarDate.isNotEmpty() -> task.calendarDate
        else -> Instant.ofEpochMilli(task.dueDateMillis).atZone(ZoneId.systemDefault()).toLocalDate().toString()
    }
    return task.copy(syncId = previous?.syncId ?: task.syncId,
        calendarDate = date, timeZone = task.timeZone.ifEmpty { previous?.timeZone.orEmpty() }.ifEmpty { ZoneId.systemDefault().id },
        syncParent = task.syncParent.ifEmpty { previous?.syncParent.orEmpty() })
}

fun initializeTaskIdentities(tasks: List<TaskEntity>): List<TaskEntity> {
    val normalized = tasks.map { normalizeLocalTask(it, null) }.associateBy { it.id }
    val identities = mutableMapOf<Int, String>()
    fun identity(id: Int, visited: Set<Int> = emptySet()): String {
        identities[id]?.let { return it }
        val task = normalized.getValue(id)
        val parent = task.recurrenceParentId?.takeIf { it in normalized && it !in visited && it != id }
        val value = if (parent != null && task.calendarDate.isNotEmpty())
            UUID.nameUUIDFromBytes("${identity(parent, visited + id)}:${task.calendarDate}".toByteArray(Charsets.UTF_8)).toString()
        else task.syncId
        identities[id] = value
        return value
    }
    normalized.keys.forEach { identity(it) }
    return normalized.values.map { it.copy(syncId = identities.getValue(it.id), syncParent = identities[it.recurrenceParentId].orEmpty()) }
}

fun LocalSyncSnapshot.toWire(): WidgetSnapshot {
    val tagIds = tags.filter { !it.deleted }.associate { it.name to it.id }
    val taskIds = tasks.associate { it.id to it.syncId }
    return WidgetSnapshot(tasks = tasks.map { task ->
        val normalized = normalizeLocalTask(task, task)
        WidgetTask(task.syncId, task.content, task.isCompleted, task.details,
            task.taskTags.mapNotNull(tagIds::get), task.priority.toInt().coerceIn(0, 4), normalized.calendarDate,
            task.dueTimeMinutes?.takeIf { normalized.calendarDate.isNotEmpty() }?.let { "%02d:%02d".format(java.util.Locale.ROOT, it / 60, it % 60) }.orEmpty(),
            normalized.timeZone, task.subtasks.map { ChecklistItem(it.id, it.content, it.isCompleted) },
            TaskRecurrence.decode(task.recurrence)?.takeIf { normalized.calendarDate.isNotEmpty() }?.let { Recurrence(it.frequency.name.lowercase(java.util.Locale.ROOT), it.interval, it.anchor) },
            createdAt = task.createdAtMillis ?: 0, deleted = task.syncDeleted, retracted = task.syncRetracted,
            parentId = task.syncParent.ifEmpty { taskIds[task.recurrenceParentId].orEmpty() }, rollover = task.rollover)
    }, tags = tags.filter { !it.deleted }.map { TaskTag(it.id, it.name, "#%06X".format(java.util.Locale.ROOT, it.color and 0xffffff), it.icon) })
}

fun WidgetSnapshot.toAndroid(previous: LocalSyncSnapshot): Pair<List<TaskEntity>, List<SyncedTag>> {
    val ids = previous.tasks.associate { it.syncId to it.id }
    val names = tags.associate { it.id to it.name }
    val converted = tasks.map { task ->
        val taskTags = task.tags.mapNotNull(names::get)
        TaskEntity(task.title, category = taskTags.firstOrNull().orEmpty(), isCompleted = task.completed,
            priority = task.priority.toFloat(), id = ids[task.id] ?: 0,
            dueDateMillis = task.dueDate.takeIf { it.isNotEmpty() }?.let { LocalDate.parse(it).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() },
            createdAtMillis = task.createdAt.takeIf { it > 0 }, details = task.details, tags = taskTags,
            dueTimeMinutes = task.dueTime.takeIf { it.isNotEmpty() }?.let { LocalTime.parse(it).let { time -> time.hour * 60 + time.minute } },
            subtasks = task.checklist.map { Subtask(it.title, it.completed, it.id) },
            recurrence = task.recurrence?.let { rule -> TaskRecurrence(RepeatFrequency.entries.first { it.name.equals(rule.frequency, true) }, rule.interval, rule.anchor).encode() },
            rollover = task.rollover ?: previous.tasks.firstOrNull { it.syncId == task.id }?.rollover ?: false,
            recurrenceParentId = ids[task.parentId], syncId = task.id, calendarDate = task.dueDate,
            timeZone = task.timeZone, syncParent = task.parentId, syncDeleted = task.deleted, syncRetracted = task.retracted)
    }
    return converted to tags.map { SyncedTag(it.id, it.name, (it.color.removePrefix("#").toLong(16) or 0xff000000L).toInt(), icon = it.icon) }
}
