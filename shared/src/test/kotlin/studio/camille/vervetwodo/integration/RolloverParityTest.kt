package studio.camille.vervetwodo.integration

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class RolloverParityTest {
    private val task = WidgetTask("task-identity-0001", "Missed task", dueDate = "2026-10-05", dueTime = "10:00", timeZone = "Europe/Paris",
        checklist = listOf(ChecklistItem("step", "Keep progress", true)), recurrence = Recurrence("monthly", anchor = "2026-10-05"), rollover = true)

    @Test fun catchesUpWithoutDuplicatingOrChangingTimeChecklistOrRecurrence() {
        val snapshot = WidgetSnapshot(revision = 8, tasks = listOf(task))
        val command = TaskRules.rollover(snapshot, LocalDate.parse("2026-10-16"))!!
        val result = command.tasks.single()
        assertEquals(task.id, result.id)
        assertEquals("2026-10-16", result.dueDate)
        assertEquals(task.dueTime, result.dueTime)
        assertEquals(task.timeZone, result.timeZone)
        assertEquals(task.checklist, result.checklist)
        assertEquals(task.recurrence, result.recurrence)
        assertEquals(8L, command.expectedRevision)
        assertNull(TaskRules.rollover(snapshot.copy(tasks = listOf(result)), LocalDate.parse("2026-10-16")))
    }

    @Test fun onlyIncompleteEnabledPastDatedTasksRollOver() {
        val variants = listOf(task.copy(completed = true), task.copy(deleted = true), task.copy(retracted = true),
            task.copy(rollover = false), task.copy(rollover = null), task.copy(dueDate = ""),
            task.copy(dueDate = "2026-10-16"), task.copy(dueDate = "2026-10-17"))
        variants.forEach { assertNull(TaskRules.rollover(WidgetSnapshot(tasks = listOf(it)), LocalDate.parse("2026-10-16"))) }
    }

    @Test fun synchronizationCarriesChoicesAndDoesNotInventLegacyOverrides() {
        val source = SyncSource("device", "Desktop", "macos")
        val legacy = task.copy(rollover = null)
        val old = TaskSync.plan(SyncJournal(), WidgetSnapshot(tasks = listOf(legacy)), source)
        assertFalse(old.outbox.any { it.field == "rollover" })
        assertEquals(true, TaskSync.project(old.baseline, WidgetSnapshot(tasks = listOf(task))).tasks.single().rollover)
        val enabled = TaskSync.plan(SyncJournal(old.baseline), WidgetSnapshot(tasks = listOf(task)), source)
        assertEquals(listOf("rollover"), enabled.outbox.map { it.field })
        assertEquals(true, TaskSync.project(enabled.baseline, WidgetSnapshot()).tasks.single().rollover)
        val disabled = TaskSync.plan(SyncJournal(enabled.baseline), WidgetSnapshot(tasks = listOf(task.copy(rollover = false))), source)
        assertEquals(false, TaskSync.project(disabled.baseline, WidgetSnapshot()).tasks.single().rollover)
    }
}
