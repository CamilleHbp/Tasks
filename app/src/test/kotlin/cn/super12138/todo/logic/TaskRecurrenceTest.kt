package cn.super12138.todo.logic

import cn.super12138.todo.logic.database.TaskEntity
import cn.super12138.todo.logic.database.Subtask
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class TaskRecurrenceTest {
    @Test fun confirmingClampedDatePreservesOriginalAnchor() {
        val zone = ZoneId.of("Europe/Paris")
        val rule = TaskRecurrence(RepeatFrequency.Monthly, anchor = "2027-01-31")
        val february = LocalDate.parse("2027-02-28").atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
        val sameDay = LocalDate.parse("2027-02-28").atStartOfDay(zone).toInstant().toEpochMilli()
        val confirmed = rule.withDueDate(february, sameDay, zone)!!
        assertEquals(rule, confirmed)
        assertEquals(LocalDate.parse("2027-03-31"), confirmed.nextDate(LocalDate.parse("2027-02-28")))
        val changed = rule.withDueDate(february,
            LocalDate.parse("2027-03-02").atStartOfDay(zone).toInstant().toEpochMilli(), zone)!!
        assertEquals("2027-03-02", changed.anchor)
        assertNull(rule.withDueDate(february, null, zone))
    }

    @Test fun monthlyReturnsToOriginalDayAfterShortMonth() {
        val rule = TaskRecurrence(RepeatFrequency.Monthly, anchor = "2027-01-29")
        val february = rule.nextDate(LocalDate.parse(rule.anchor))
        assertEquals(LocalDate.parse("2027-02-28"), february)
        assertEquals(LocalDate.parse("2027-03-29"), rule.nextDate(february))
        val last = rule.copy(anchor = "2028-01-31")
        assertEquals(LocalDate.parse("2028-02-29"), last.nextDate(LocalDate.parse(last.anchor)))
        assertEquals(LocalDate.parse("2028-03-31"), last.nextDate(LocalDate.parse("2028-02-29")))
    }

    @Test fun leapAnniversaryRecoversAndIntervalsAreCalendarBased() {
        val rule = TaskRecurrence(RepeatFrequency.Yearly, anchor = "2024-02-29")
        assertEquals(LocalDate.parse("2025-02-28"), rule.nextDate(LocalDate.parse(rule.anchor)))
        assertEquals(LocalDate.parse("2028-02-29"), rule.nextDate(LocalDate.parse("2027-02-28")))
        assertEquals(LocalDate.parse("2026-10-17"), TaskRecurrence(RepeatFrequency.Weekly, 2, "2026-10-03")
            .nextDate(LocalDate.parse("2026-10-03")))
    }

    @Test fun nextOccurrencePreservesWallClockTimeAcrossDstAndResetsChecklist() {
        val zone = ZoneId.of("Europe/Paris")
        val task = TaskEntity("Water the plants", priority = 1f, id = 42, tags = listOf("Home"),
            dueDateMillis = LocalDate.parse("2027-03-27").atTime(10, 0).atZone(zone).toInstant().toEpochMilli(),
            dueTimeMinutes = 600, subtasks = listOf(Subtask("Balcony", true)),
            recurrence = TaskRecurrence(RepeatFrequency.Daily, anchor = "2027-03-27").encode())
        val next = task.nextOccurrence(zone)!!
        assertEquals(LocalDate.parse("2027-03-28").atTime(10, 0).atZone(zone).toInstant().toEpochMilli(), next.dueDateMillis)
        assertEquals(42, next.recurrenceParentId)
        assertEquals(0, next.id)
        assertFalse(next.isCompleted)
        assertFalse(next.subtasks.single().isCompleted)
        assertEquals(task.tags, next.tags)
        assertNull(TaskRecurrence.decode("invalid"))
    }
}
