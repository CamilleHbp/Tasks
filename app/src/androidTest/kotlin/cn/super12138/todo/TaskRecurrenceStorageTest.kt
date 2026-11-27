package cn.super12138.todo

import androidx.room3.Room
import androidx.test.platform.app.InstrumentationRegistry
import cn.super12138.todo.logic.*
import cn.super12138.todo.logic.database.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class TaskRecurrenceStorageTest {
    @Test fun completionUndoAndEditorSaveGenerateExactlyOneOccurrence() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, TaskDatabase::class.java).build()
        try {
            val dao = db.taskDao()
            val repo = TaskRepository(dao)
            val task = TaskEntity("Monthly review", priority = 1f, id = 42,
                dueDateMillis = LocalDate.parse("2027-01-29").atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                recurrence = TaskRecurrence(RepeatFrequency.Monthly, anchor = "2027-01-29").encode())
            repo.insertTask(task)
            repo.completeTask(42)
            repo.completeTask(42)
            assertEquals(2, dao.count())
            assertTrue(dao.getTask(42)!!.isCompleted)
            assertFalse(dao.nextFor(42)!!.isCompleted)
            repo.restoreTask(42)
            assertEquals(1, dao.count())
            repo.insertTask(task.copy(isCompleted = true))
            assertEquals(2, dao.count())
            val next = dao.nextFor(42)!!
            repo.updateTask(next.copy(details = "Edited next occurrence"))
            repo.restoreTask(42)
            assertEquals(2, dao.count())
            repo.completeTask(42)
            assertEquals(2, dao.count())
            assertEquals("Edited next occurrence", dao.nextFor(42)!!.details)
            repo.editTasks(setOf(42, next.id), BulkTaskEdit(priority = -1f))
            assertTrue(dao.getAll().first().all { it.priority == -1f })
        } finally { db.close() }
    }
}
