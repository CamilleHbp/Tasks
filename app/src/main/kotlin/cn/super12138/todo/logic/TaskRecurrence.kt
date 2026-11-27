package cn.super12138.todo.logic

import cn.super12138.todo.logic.database.TaskEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Serializable
enum class RepeatFrequency { Daily, Weekly, Monthly, Yearly }

/** The anchor preserves the requested day across February and other short months. */
@Serializable
data class TaskRecurrence(val frequency: RepeatFrequency, val interval: Int = 1, val anchor: String) {
    init { require(interval in 1..999); LocalDate.parse(anchor) }

    fun encode(): String = Json.encodeToString(this)

    fun nextDate(current: LocalDate): LocalDate {
        val original = LocalDate.parse(anchor)
        return when (frequency) {
            RepeatFrequency.Daily -> current.plusDays(interval.toLong())
            RepeatFrequency.Weekly -> current.plusWeeks(interval.toLong())
            RepeatFrequency.Monthly -> current.withDayOfMonth(1).plusMonths(interval.toLong()).let {
                it.withDayOfMonth(minOf(original.dayOfMonth, it.lengthOfMonth()))
            }
            RepeatFrequency.Yearly -> LocalDate.of(current.year + interval, original.month, 1).let {
                it.withDayOfMonth(minOf(original.dayOfMonth, it.lengthOfMonth()))
            }
        }
    }

    companion object {
        fun decode(value: String?): TaskRecurrence? = value?.let {
            runCatching { Json.decodeFromString<TaskRecurrence>(it) }.getOrNull()
        }
    }
}

fun TaskEntity.nextOccurrence(zone: ZoneId = ZoneId.systemDefault()): TaskEntity? {
    val rule = TaskRecurrence.decode(recurrence) ?: return null
    val due = dueDateMillis ?: return null
    val current = calendarDate.takeIf { it.isNotEmpty() }?.let(LocalDate::parse) ?: Instant.ofEpochMilli(due).atZone(zone).toLocalDate()
    val next = rule.nextDate(current)
    return copy(id = 0, isCompleted = false, createdAtMillis = null,
        syncId = java.util.UUID.nameUUIDFromBytes("$syncId:$next".toByteArray(Charsets.UTF_8)).toString(),
        calendarDate = next.toString(), syncParent = syncId, syncDeleted = false, syncRetracted = false,
        dueDateMillis = next.atTime(java.time.LocalTime.ofSecondOfDay((dueTimeMinutes ?: 0) * 60L)).atZone(zone).toInstant().toEpochMilli(),
        subtasks = subtasks.map { it.copy(isCompleted = false) }, recurrenceParentId = id)
}

/** Confirming a clamped occurrence's unchanged day must not replace the original anchor. */
fun TaskRecurrence?.withDueDate(previous: Long?, updated: Long?, zone: ZoneId = ZoneId.systemDefault()): TaskRecurrence? {
    if (this == null || updated == null) return null
    val day = Instant.ofEpochMilli(updated).atZone(zone).toLocalDate()
    val previousDay = previous?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
    return if (previousDay == day) this else copy(anchor = day.toString())
}
