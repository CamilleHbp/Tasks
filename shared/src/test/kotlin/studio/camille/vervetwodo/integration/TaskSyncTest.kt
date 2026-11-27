package studio.camille.vervetwodo.integration

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class TaskSyncTest {
    private val source = SyncSource(UUID.randomUUID().toString(), "Test", "android")
    private fun task() = WidgetTask(UUID.randomUUID().toString(), "A task", createdAt = 1234)
    @Test fun categoryIconSurvivesSyncRenameAndBackup() {
        val tag = TaskTag(UUID.randomUUID().toString(), "Cinema", icon = "cinema")
        val snapshot = WidgetSnapshot(tags = listOf(tag))
        val initial = TaskSync.plan(SyncJournal(), snapshot, source)
        assertEquals("cinema", TaskSync.project(initial.baseline, WidgetSnapshot()).tags.single().icon)
        val renamed = snapshot.copy(tags = listOf(tag.copy(name = "Films")))
        val change = TaskSync.plan(SyncJournal(initial.baseline), renamed, source)
        assertEquals(listOf("name"), change.outbox.map { it.field })
        assertEquals("cinema", protocolJson.decodeFromString<WidgetSnapshot>(protocolJson.encodeToString(renamed)).tags.single().icon)
        assertEquals("tag", protocolJson.decodeFromString<TaskTag>("""{"id":"legacy","name":"Old","color":"#3877B8"}""").icon)
        assertEquals("tag", CategoryIcons.displayId("future-icon"))
        assertEquals(360f, CategoryIcons.segmentSweep(3) * 3, .001f)
    }
    @Test fun firstConnectionUnionsTasksAndPreservesCausalVersions() {
        val local = task()
        val journal = TaskSync.plan(SyncJournal(), WidgetSnapshot(tasks = listOf(local)), source)
        val remote = task().copy(title = "Remote")
        val other = TaskSync.plan(SyncJournal(), WidgetSnapshot(tasks = listOf(remote)), source)
        val projected = TaskSync.project(journal.baseline + other.baseline, WidgetSnapshot(tasks = listOf(local)))
        assertEquals(2, projected.tasks.size)
        assertTrue(TaskSync.plan(SyncJournal(journal.baseline + other.baseline), projected, source).outbox.isEmpty())
        val changed = projected.copy(tasks = projected.tasks.map { if (it.id == local.id) it.copy(details = "An offline edit") else it })
        val plan = TaskSync.plan(SyncJournal(journal.baseline + other.baseline), changed, source)
        assertEquals(listOf("details"), plan.outbox.map { it.field })
        assertEquals(journal.baseline.single().fields.getValue("details").map { it.id }, plan.outbox.single().replaces)
    }
    @Test fun conflictSurvivesProjectionUntilExplicitlyEdited() {
        val task = task()
        val baseline = TaskSync.plan(SyncJournal(), WidgetSnapshot(tasks = listOf(task)), source).baseline
        val id = baseline.single().fields.getValue("title").single().id
        val ops = listOf("A", "B").map { SyncOperation(record = task.id, kind = "task", field = "title", value = it, replaces = listOf(id)) }
        val conflicted = TaskSync.predict(baseline, ops)
        val projection = TaskSync.project(conflicted, WidgetSnapshot())
        assertEquals(2, conflicted.single().conflicts().getValue("title").size)
        assertTrue(TaskSync.plan(SyncJournal(conflicted), projection, source).outbox.isEmpty())
    }
    @Test fun batchesRespectUnicodeAndCountBounds() {
        val operations = (1..800).map { SyncOperation(record = UUID.randomUUID().toString(), kind = "task", field = "details", value = "É😎".repeat(400)) }
        val batch = TaskSync.batch(operations)
        assertTrue(batch.size in 1..200)
        assertTrue(protocolJson.encodeToString(SyncBatch(batch)).toByteArray().size < 200000)
    }
    @Test fun recurringCompletionUsesSameSuccessorAndUndoRetainsEdits() {
        val parent = task().copy(dueDate = "2026-10-05", recurrence = Recurrence("daily", anchor = "2026-10-05"))
        val snapshot = WidgetSnapshot(tasks = listOf(parent))
        val a = TaskRules.completion(snapshot, setOf(parent.id), true)
        val b = TaskRules.completion(snapshot, setOf(parent.id), true)
        val childA = a.tasks.single { it.parentId == parent.id }
        val childB = b.tasks.single { it.parentId == parent.id }
        assertEquals(TaskSync.fields(childA), TaskSync.fields(childB))
        val edited = childA.copy(details = "Edited on another device", generation = 2)
        val current = snapshot.copy(tasks = a.tasks.map { if (it.id == edited.id) edited else it })
        assertFalse(TaskRules.completion(current, setOf(parent.id), false).tasks.any { it.id == edited.id })
    }
}
