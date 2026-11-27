package cn.super12138.todo.logic.reminders

import cn.super12138.todo.logic.database.TaskEntity
import java.time.*

fun TaskEntity.reminderTime(defaultMinutes: Int, zone: ZoneId = ZoneId.systemDefault()): Long? {
    if (isCompleted || syncDeleted || syncRetracted) return null
    val date = calendarDate.takeIf(String::isNotBlank)?.let(LocalDate::parse)
        ?: dueDateMillis?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } ?: return null
    val minutes = dueTimeMinutes ?: defaultMinutes
    val taskZone = if (dueTimeMinutes != null && timeZone.isNotBlank()) ZoneId.of(timeZone) else zone
    return date.atTime(LocalTime.of(minutes / 60, minutes % 60)).atZone(taskZone).toInstant().toEpochMilli()
}
