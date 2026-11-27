package cn.super12138.todo.logic

import cn.super12138.todo.logic.database.TaskEntity
import cn.super12138.todo.logic.database.taskTags

enum class BulkTagAction { Add, Remove, Replace }

data class BulkTaskEdit(
    val tags: List<String>? = null,
    val tagAction: BulkTagAction = BulkTagAction.Add,
    val priority: Float? = null,
    val changeDueDate: Boolean = false,
    val dueDateMillis: Long? = null,
    val dueTimeMinutes: Int? = null
) {
    fun applyTo(task: TaskEntity): TaskEntity {
        val updatedTags = when {
            tags == null -> task.taskTags
            tagAction == BulkTagAction.Add -> (task.taskTags + tags).distinct()
            tagAction == BulkTagAction.Remove -> task.taskTags - tags.toSet()
            else -> tags.distinct()
        }
        val recurrence = if (!changeDueDate) task.recurrence else TaskRecurrence.decode(task.recurrence)
            .withDueDate(task.dueDateMillis, dueDateMillis)?.encode()
        return task.copy(tags = updatedTags, category = updatedTags.firstOrNull().orEmpty(),
            priority = priority ?: task.priority,
            dueDateMillis = if (changeDueDate) dueDateMillis?.let { dueTimestamp(it, dueTimeMinutes) } else task.dueDateMillis,
            dueTimeMinutes = if (changeDueDate) dueTimeMinutes.takeIf { dueDateMillis != null } else task.dueTimeMinutes,
            recurrence = recurrence)
    }
}
