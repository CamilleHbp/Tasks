package cn.super12138.todo.logic

import cn.super12138.todo.logic.database.TaskDao
import cn.super12138.todo.logic.database.TaskEntity
import cn.super12138.todo.logic.database.taskTags
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class TaskRepository(
    private val taskDao: TaskDao,
    private val onTasksChanged: suspend () -> Unit = {}
) {
    suspend fun rollForward() {
        if (taskDao.rollForward()) onTasksChanged()
    }

    suspend fun insertTask(task: TaskEntity) {
        taskDao.saveWithRecurrence(
            if (task.id == 0 && task.createdAtMillis == null) {
                task.copy(createdAtMillis = System.currentTimeMillis())
            } else task
        )
        onTasksChanged()
    }

    fun getAllTasks(): Flow<List<TaskEntity>> = taskDao.getAll()

    fun observeTask(taskId: Int): Flow<TaskEntity?> = taskDao.observeTask(taskId)

    suspend fun setSubtaskCompleted(taskId: Int, subtaskId: String, completed: Boolean) {
        taskDao.setSubtaskCompleted(taskId, subtaskId, completed)
        onTasksChanged()
    }

    fun getCategories(): Flow<List<String>> = taskDao.getAll().map { tasks -> tasks.flatMap { it.taskTags }.distinct() }

    suspend fun replaceTag(old: String, replacement: String?) {
        taskDao.replaceTag(old, replacement)
        onTasksChanged()
    }

    suspend fun updateTask(task: TaskEntity) {
        taskDao.saveWithRecurrence(task)
        onTasksChanged()
    }

    suspend fun editTasks(ids: Set<Int>, edit: BulkTaskEdit) {
        taskDao.editTasks(ids, edit)
        onTasksChanged()
    }

    suspend fun completeTask(taskId: Int) {
        taskDao.completeTask(taskId)
        onTasksChanged()
    }

    suspend fun restoreTask(taskId: Int) {
        taskDao.restoreTask(taskId)
        onTasksChanged()
    }

    suspend fun deleteTask(task: TaskEntity) {
        taskDao.delete(task)
        onTasksChanged()
    }

    suspend fun deleteTaskFromIds(tasks: Set<Int>) {
        taskDao.deleteFromIds(tasks)
        onTasksChanged()
    }

    /*suspend fun deleteAllTodo() {
        toDoDao.deleteAllTodo()
    }*/
}
