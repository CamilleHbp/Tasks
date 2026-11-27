package cn.super12138.todo

import androidx.room3.Room
import androidx.test.platform.app.InstrumentationRegistry
import cn.super12138.todo.logic.TaskRepository
import cn.super12138.todo.logic.database.Subtask
import cn.super12138.todo.logic.database.TaskDatabase
import cn.super12138.todo.logic.database.TaskEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TaskWidgetRefreshTest {
    private lateinit var database: TaskDatabase

    @Before fun prepare() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            TaskDatabase::class.java
        ).build()
    }

    @After fun cleanUp() {
        database.close()
    }

    @Test fun saveRefreshesCommittedDataBeforeReportingSuccess() = runBlocking {
        withTimeout(10_000) {
            val refreshStarted = CompletableDeferred<TaskEntity>()
            val allowRefreshToFinish = CompletableDeferred<Unit>()
            val repository = TaskRepository(database.taskDao()) {
                refreshStarted.complete(database.taskDao().getTask(1)!!)
                allowRefreshToFinish.await()
            }
            val save = launch {
                repository.insertTask(TaskEntity("New widget task", priority = 0f, id = 1))
            }
            val saved = refreshStarted.await()
            assertEquals("New widget task", saved.content)
            assertFalse("Save must enqueue the refresh before completing", save.isCompleted)
            allowRefreshToFinish.complete(Unit)
            save.join()
            assertTrue(save.isCompleted)
        }
    }

    @Test fun allTaskMutationsRefreshTheirCommittedState() = runBlocking {
        val snapshots = mutableListOf<List<TaskEntity>>()
        val repository = TaskRepository(database.taskDao()) {
            snapshots += database.taskDao().getTasksForTagUpdate()
        }
        repository.insertTask(TaskEntity("Task", category = "Work", priority = 0f,
            subtasks = listOf(Subtask("Step", id = "step"))))
        val saved = snapshots.last().single()
        assertNotNull(saved.createdAtMillis)
        repository.updateTask(saved.copy(content = "Edited"))
        repository.setSubtaskCompleted(saved.id, "step", true)
        repository.replaceTag("Work", "Home")
        repository.completeTask(saved.id)
        repository.restoreTask(saved.id)
        repository.deleteTask(saved)
        repository.insertTask(saved)
        repository.deleteTaskFromIds(setOf(saved.id))

        assertEquals(9, snapshots.size)
        assertEquals("Edited", snapshots[1].single().content)
        assertTrue(snapshots[2].single().subtasks.single().isCompleted)
        assertEquals("Home", snapshots[3].single().category)
        assertTrue(snapshots[4].single().isCompleted)
        assertFalse(snapshots[5].single().isCompleted)
        assertTrue(snapshots[6].isEmpty())
        assertTrue(snapshots[8].isEmpty())
    }

    @Test fun failedWriteDoesNotRequestARefresh() = runBlocking {
        var refreshes = 0
        val repository = TaskRepository(database.taskDao()) { refreshes++ }
        database.close()

        val result = runCatching {
            repository.insertTask(TaskEntity("Cannot save", priority = 0f))
        }

        assertTrue(result.isFailure)
        assertEquals(0, refreshes)
    }
}
