package cn.super12138.todo.logic

import cn.super12138.todo.logic.database.*
import cn.super12138.todo.logic.sync.*
import org.junit.Assert.*
import org.junit.Test
import studio.camille.vervetwodo.integration.*
import java.util.UUID
import java.util.TimeZone

class TaskSyncMappingTest {
    @Test fun retractedTaskAndDateOnlyKeepWireValuesAcrossTimezones() {
        val old = TimeZone.getDefault()
        try {
            val id = UUID.randomUUID().toString()
            val task = WidgetTask(id, "A task", dueDate = "2026-10-25", timeZone = "Europe/Paris", deleted = true, retracted = true,
                checklist = listOf(ChecklistItem(UUID.randomUUID().toString(), "First step")), createdAt = 1234, rollover = false)
            val snapshot = WidgetSnapshot(tasks = listOf(task))
            val previous = LocalSyncSnapshot(SyncEnvelope(), emptyList(), emptyList())
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            val (tasks, tags) = snapshot.toAndroid(previous)
            val android = tasks.single().copy(id = 55)
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"))
            val result = LocalSyncSnapshot(SyncEnvelope(), listOf(android), tags).toWire().tasks.single()
            assertEquals(TaskSync.fields(task), TaskSync.fields(result))
            assertEquals(55, snapshot.toAndroid(LocalSyncSnapshot(SyncEnvelope(), listOf(android), tags)).first.single().id)
        } finally { TimeZone.setDefault(old) }
    }
    @Test fun rolloverPreservesLegacyAndroidChoiceAndAcceptsExplicitDesktopChanges() {
        val id = UUID.randomUUID().toString()
        val local = TaskEntity("Missed", priority = 0f, id = 1, syncId = id, rollover = true)
        val previous = LocalSyncSnapshot(SyncEnvelope(), listOf(local), emptyList())
        assertEquals(true, previous.toWire().tasks.single().rollover)
        val legacy = WidgetSnapshot(tasks = listOf(WidgetTask(id, "Missed")))
        assertTrue(legacy.toAndroid(previous).first.single().rollover)
        val disabled = legacy.copy(tasks = listOf(legacy.tasks.single().copy(rollover = false)))
        assertFalse(disabled.toAndroid(previous).first.single().rollover)
    }
    @Test fun androidAndDesktopGenerateIdenticalOccurrenceIdentities() {
        val id = UUID.randomUUID().toString()
        val wire = WidgetTask(id, "Repeat", dueDate = "2026-01-31", timeZone = "Europe/Paris", createdAt = 1234,
            recurrence = Recurrence("monthly", anchor = "2026-01-31"))
        val android = WidgetSnapshot(tasks = listOf(wire)).toAndroid(LocalSyncSnapshot(SyncEnvelope(), emptyList(), emptyList())).first.single()
        val next = android.nextOccurrence()!!
        val desktop = TaskRules.completion(WidgetSnapshot(tasks = listOf(wire)), setOf(id), true).tasks.single { it.parentId == id }
        assertEquals(desktop.id, next.syncId)
        assertEquals(desktop.dueDate, next.calendarDate)
    }
}
