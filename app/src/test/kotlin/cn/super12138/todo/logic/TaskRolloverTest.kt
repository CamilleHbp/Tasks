package cn.super12138.todo.logic
import cn.super12138.todo.logic.database.TaskEntity
import cn.super12138.todo.logic.database.Subtask
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class TaskRolloverTest {
    private val zone = ZoneId.of("Europe/Paris")
    private val today = LocalDate.parse("2027-03-28")
    private val task = TaskEntity("Test", priority = 0f, rollover = true,
        dueDateMillis = today.minusDays(3).atTime(10, 0).atZone(zone).toInstant().toEpochMilli(),
        dueTimeMinutes = 600, subtasks = listOf(Subtask("Keep checked", true)))
    @Test fun catchesUpAcrossDstAndKeepsIdentityTimeAndChecklist() {
        val result = task.rolledForward(today, zone)
        assertEquals(today.atTime(10, 0).atZone(zone).toInstant().toEpochMilli(), result.dueDateMillis)
        assertEquals(task.syncId, result.syncId)
        assertEquals(task.subtasks, result.subtasks)
        assertEquals(result, result.rolledForward(today, zone))
    }
    @Test fun ignoresCompletedDisabledDeletedUndatedAndFutureTasks() {
        listOf(task.copy(isCompleted = true), task.copy(rollover = false),
            task.copy(syncDeleted = true), task.copy(dueDateMillis = null),
            task.copy(dueDateMillis = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())).forEach {
            assertEquals(it, it.rolledForward(today, zone))
        }
    }
}
