package studio.camille.vervetwodo.integration

import org.junit.Assert.*
import org.junit.Test

class SyncConflictsTest {
    @Test fun conflictsIncludeDeletedItemsAndIgnoreIdenticalValues() {
        val record = SyncRecord("task", "task", mapOf(
            "deleted" to listOf(SyncVersion("1", "true")),
            "title" to listOf(SyncVersion("2", "A"), SyncVersion("3", "B")),
            "details" to listOf(SyncVersion("4", "Same"), SyncVersion("5", "Same"))))
        assertEquals(listOf("title"), SyncState(records = listOf(record)).conflicts().map { it.field })
    }
    @Test fun alternativesAreCompleteAndStructuredValuesAreReadable() {
        val state = SyncState(records = listOf(SyncRecord("tag", "tag", mapOf("name" to listOf(SyncVersion("1", "Work"))))))
        assertEquals("Work", state.conflictValue("tags", "[\"tag\"]") { it })
        val long = "Full details ".repeat(200)
        assertEquals(long, state.conflictValue("details", long) { it })
        assertEquals("syncDeleted", state.conflictValue("deleted", "true") { it })
        assertEquals("syncKept", state.conflictValue("deleted", "false") { it })
        assertEquals("syncEmpty", state.conflictValue("details", "") { it })
        assertEquals("broken", state.conflictValue("checklist", "broken") { it })
        assertEquals("syncCompleted: First\nsyncTodo: Second", state.conflictValue("checklist",
            protocolJson.encodeToString(listOf(ChecklistItem("1", "First", true), ChecklistItem("2", "Second")))) { it })
    }
}
