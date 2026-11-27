package cn.super12138.todo.logic

import cn.super12138.todo.logic.database.TaskEntity
import cn.super12138.todo.logic.reminders.reminderTime
import java.time.*
import org.junit.Assert.*
import org.junit.Test

class ReminderTimeTest {
    private val zone = ZoneId.of("Europe/Paris")
    private val task = TaskEntity("Task", priority = 0f, calendarDate = "2026-10-25")
    @Test fun dateOnlyUsesDefaultInDeviceZoneAcrossDst() {
        assertEquals(Instant.parse("2026-10-25T08:00:00Z").toEpochMilli(), task.reminderTime(540, zone))
        assertEquals(Instant.parse("2026-10-25T13:30:00Z").toEpochMilli(), task.reminderTime(870, zone))
    }
    @Test fun explicitTimeAndZoneOverrideDefault() {
        assertEquals(Instant.parse("2026-10-25T14:00:00Z").toEpochMilli(),
            task.copy(dueTimeMinutes = 600, timeZone = "America/New_York").reminderTime(540, zone))
    }
    @Test fun ignoresUndatedCompletedAndDeletedTasks() {
        assertNull(task.copy(calendarDate = "", dueTimeMinutes = 600).reminderTime(540, zone))
        assertNull(task.copy(isCompleted = true).reminderTime(540, zone))
        assertNull(task.copy(syncDeleted = true).reminderTime(540, zone))
        assertNull(task.copy(syncRetracted = true).reminderTime(540, zone))
    }
    @Test fun supportsLegacyDate() {
        assertEquals(task.reminderTime(540, zone), task.copy(calendarDate = "", dueDateMillis =
            LocalDate.parse("2026-10-25").atStartOfDay(zone).toInstant().toEpochMilli()).reminderTime(540, zone))
    }
}
