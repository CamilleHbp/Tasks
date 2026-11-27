package cn.super12138.todo.logic

import cn.super12138.todo.logic.database.TaskEntity
import org.junit.Assert.*
import org.junit.Test

class BulkTaskEditTest {
    private val task = TaskEntity("Keep title", category = "Work", priority = 2f, dueDateMillis = 1790006400000,
        dueTimeMinutes = 600, details = "Keep details", tags = listOf("Work", "Personal"),
        recurrence = TaskRecurrence(RepeatFrequency.Monthly, anchor = "2026-09-21").encode())

    @Test fun addingTagsKeepsUnselectedFieldsAndAvoidsDuplicates() {
        val edited = BulkTaskEdit(tags = listOf("Work", "Home")).applyTo(task)
        assertEquals(listOf("Work", "Personal", "Home"), edited.tags)
        assertEquals(task.copy(tags = edited.tags), edited)
    }

    @Test fun removalReplacementAndDateClearingAreExplicit() {
        assertEquals(listOf("Personal"), BulkTaskEdit(tags = listOf("Work"), tagAction = BulkTagAction.Remove).applyTo(task).tags)
        val cleared = BulkTaskEdit(tags = emptyList(), tagAction = BulkTagAction.Replace, changeDueDate = true).applyTo(task)
        assertTrue(cleared.tags.isEmpty())
        assertEquals("", cleared.category)
        assertNull(cleared.dueDateMillis)
        assertNull(cleared.dueTimeMinutes)
        assertNull(cleared.recurrence)
        assertEquals(task.content, cleared.content)
        assertEquals(task.details, cleared.details)
    }
}
