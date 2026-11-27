package studio.camille.vervetwodo.integration

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

class TaskRulesTest {
    private val task = WidgetTask("11111111-1111-4111-8111-111111111111", "Préparer le voyage", dueDate = "2026-01-31",
        recurrence = Recurrence("monthly", anchor = "2026-01-31"), checklist = listOf(ChecklistItem("step", "Billets", true)))
    private fun apply(snapshot: WidgetSnapshot, command: TaskCommand) = snapshot.copy(revision = snapshot.revision + 1,
        tasks = snapshot.tasks.filterNot { old -> command.tasks.any { it.id == old.id } } + command.tasks,
        tags = command.tags ?: snapshot.tags)

    @Test fun monthlyRecurrenceRetainsAnchorAndHasDeterministicIdentity() {
        val snapshot = WidgetSnapshot(tasks = listOf(task))
        val command = TaskRules.completion(snapshot, setOf(task.id), true)
        val successor = command.tasks.single { it.id != task.id }
        assertEquals("2026-02-28", successor.dueDate)
        assertFalse(successor.checklist.single().completed)
        assertEquals(successor.id, TaskRules.completion(snapshot, setOf(task.id), true).tasks.last().id)
        assertEquals(LocalDate.parse("2026-03-31"), TaskRules.nextDate(LocalDate.parse(successor.dueDate), successor.recurrence!!))
    }
    @Test fun undoPreservesAnEditedSuccessor() {
        val before = WidgetSnapshot(tasks = listOf(task))
        val command = TaskRules.completion(before, setOf(task.id), true)
        val completed = apply(before, command)
        val successor = completed.tasks.single { it.id != task.id }
        val edited = apply(completed, TaskRules.transaction(completed, listOf(successor.copy(title = "Booked"))))
        val undone = apply(edited, TaskRules.undo(before, command, edited))
        assertFalse(undone.tasks.single { it.id == task.id }.completed)
        assertEquals("Booked", undone.tasks.single { it.id == successor.id }.title)
        assertFalse(undone.tasks.single { it.id == successor.id }.deleted)
    }
    @Test fun undoAndRepeatRestoresUntouchedSuccessorOnce() {
        val before = WidgetSnapshot(tasks = listOf(task))
        val command = TaskRules.completion(before, setOf(task.id), true)
        val completed = apply(before, command)
        val undone = apply(completed, TaskRules.undo(before, command, completed))
        assertEquals(1, undone.tasks.count { !it.deleted })
        val again = apply(undone, TaskRules.completion(undone, setOf(task.id), true))
        assertEquals(2, again.tasks.count { !it.deleted })
        assertEquals(2, again.tasks.size)
    }
    @Test fun explicitSuccessorDeletionIsNotReversedByCompletion() {
        val before = WidgetSnapshot(tasks = listOf(task))
        val completed = apply(before, TaskRules.completion(before, setOf(task.id), true))
        val successor = completed.tasks.single { it.id != task.id }
        val deleted = apply(completed, TaskRules.transaction(completed, listOf(successor.copy(deleted = true))))
        val reopened = apply(deleted, TaskRules.completion(deleted, setOf(task.id), false))
        val again = apply(reopened, TaskRules.completion(reopened, setOf(task.id), true))
        assertTrue(again.tasks.single { it.id == successor.id }.deleted)
    }
    @Test fun dateOnlyAndTimedValidation() {
        TaskRules.validate(task.copy(timeZone = "", dueTime = ""))
        TaskRules.validate(task.copy(dueTime = "09:30", timeZone = "Europe/Paris"))
        listOf(task.copy(dueDate = "2026-02-30"), task.copy(dueTime = "25:00", timeZone = "Europe/Paris"),
            task.copy(dueTime = "09:00", timeZone = "invalid"), task.copy(dueDate = "")).forEach {
            assertTrue(runCatching { TaskRules.validate(it) }.isFailure)
        }
    }
    @Test fun everyAgendaTaskHasOneHomeAndSearchIncludesChecklistAndTags() {
        val today = LocalDate.parse("2026-10-05")
        val tasks = (0..30).map { offset -> task.copy(id = "$offset-11111111-1111-4111", dueDate = today.plusDays(offset.toLong() - 3).toString()) } +
            task.copy(id = "undated-11111111-1111", dueDate = "", recurrence = null, tags = listOf("tag-id"))
        val snapshot = WidgetSnapshot(tasks = tasks, tags = listOf(TaskTag("tag-id", "Voyage")))
        val grouped = taskGroups(snapshot, "all", "due", null, "", today, Locale.FRENCH).flatMap { it.tasks }
        assertEquals(tasks.size, grouped.size)
        assertEquals(tasks.size, grouped.map { it.id }.distinct().size)
        assertEquals(1, taskGroups(snapshot, "all", "due", null, "voyage", today).size)
        assertEquals(tasks.size, taskGroups(snapshot, "all", "due", null, "billets", today).single().tasks.size)
    }
    @Test fun backupImportKeepsExistingRecordsAndRemapsIdentities() {
        val snapshot = WidgetSnapshot(tasks = listOf(task))
        val restored = apply(snapshot, Backups.import(snapshot, Backups.encode(snapshot)))
        assertEquals(2, restored.tasks.size)
        assertEquals(task, restored.tasks.single { it.id == task.id })
        assertEquals(2, restored.tasks.map { it.id }.distinct().size)
        assertTrue(runCatching { Backups.import(snapshot, "{\"format\":\"unknown\"}") }.isFailure)
    }
    @Test fun legacyPreviewTasksDecodeWithSafeDefaults() {
        val snapshot = protocolJson.decodeFromString<WidgetSnapshot>("""{"version":1,"revision":1,"locale":"en","tasks":[{"id":"11111111-1111-4111-8111-111111111111","title":"Old task","completed":false}]}""")
        assertEquals("", snapshot.tasks.single().dueDate)
        assertTrue(snapshot.tags.isEmpty())
    }
    @Test fun remindersUseExplicitTimezoneAndDeduplicateDueTime() {
        val timed = task.copy(dueDate = "2026-10-25", dueTime = "09:00", timeZone = "Europe/Paris")
        val snapshot = WidgetSnapshot(tasks = listOf(timed, timed.copy(id = "other-111111111111", dueTime = ""), timed.copy(id = "done-111111111111", completed = true)))
        val now = java.time.Instant.parse("2026-10-24T12:00:00Z")
        val due = reminders(snapshot, true, 0, "body", now, localZone = java.time.ZoneId.of("Europe/Paris"))
        assertEquals(2, due.size)
        assertEquals(java.time.Instant.parse("2026-10-25T08:00:00Z").epochSecond, due.first().epochSeconds)
        assertEquals(4, reminders(snapshot, true, 15, "body", now).size)
        assertTrue(reminders(snapshot, false, 0, "body", now).isEmpty())
        assertTrue(reminders(snapshot, true, 0, "body", java.time.Instant.parse("2026-10-26T00:00:00Z")).isEmpty())
    }
    @Test fun dateOnlyRemindersUseConfiguredDeviceTimeAndIgnoreUndatedTasks() {
        val dateOnly = task.copy(dueDate = "2026-10-25", dueTime = "", timeZone = "America/New_York")
        val snapshot = WidgetSnapshot(tasks = listOf(dateOnly, dateOnly.copy(id = "undated", dueDate = "", dueTime = "12:00"),
            dateOnly.copy(id = "deleted", deleted = true)))
        val result = reminders(snapshot, true, 0, "body", java.time.Instant.parse("2026-10-24T00:00:00Z"),
            java.time.LocalTime.of(14, 30), java.time.ZoneId.of("Europe/Paris"))
        assertEquals(1, result.size)
        assertEquals(java.time.Instant.parse("2026-10-25T13:30:00Z").epochSecond, result.single().epochSeconds)
    }

}
