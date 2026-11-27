package studio.camille.vervetwodo.integration

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@Serializable
data class ScheduledReminder(val id: String, val taskId: String, val title: String, val body: String, val epochSeconds: Long)

fun reminders(snapshot: WidgetSnapshot, enabled: Boolean, advanceMinutes: Int, body: String,
              now: Instant = Instant.now(), defaultTime: LocalTime = LocalTime.of(9, 0),
              localZone: ZoneId = ZoneId.systemDefault()): List<ScheduledReminder> {
    if (!enabled) return emptyList()
    require(advanceMinutes in 0..10080)
    return snapshot.tasks.filter { !it.completed && !it.deleted && it.dueDate.isNotEmpty() }.flatMap { task ->
        val due = LocalDate.parse(task.dueDate).atTime(task.dueTime.takeIf { it.isNotEmpty() }?.let(LocalTime::parse) ?: defaultTime).atZone(
            if (task.dueTime.isEmpty() || task.timeZone.isEmpty()) localZone else ZoneId.of(task.timeZone)).toInstant()
        listOf(0, advanceMinutes).distinct().mapNotNull { offset ->
            val at = due.minusSeconds(offset * 60L)
            if (at <= now) null else ScheduledReminder("verve-task-${task.id}-${at.epochSecond}", task.id, task.title, body, at.epochSecond)
        }
    }.sortedBy { it.epochSeconds }.take(60)
}
