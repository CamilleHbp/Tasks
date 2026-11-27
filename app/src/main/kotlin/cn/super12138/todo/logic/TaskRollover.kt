package cn.super12138.todo.logic

import cn.super12138.todo.logic.database.TaskEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Catch up missed days without duplicating tasks or resetting their checklist. */
fun TaskEntity.rolledForward(today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): TaskEntity {
    val due = dueDateMillis ?: return this
    if (!rollover || isCompleted || syncDeleted || syncRetracted) return this
    val date = calendarDate.takeIf { it.isNotEmpty() }?.let(LocalDate::parse)
        ?: Instant.ofEpochMilli(due).atZone(zone).toLocalDate()
    if (!date.isBefore(today)) return this
    val timestamp = today.atStartOfDay(zone).toInstant().toEpochMilli()
    return copy(dueDateMillis = dueTimestamp(timestamp, dueTimeMinutes, zone), calendarDate = today.toString())
}
