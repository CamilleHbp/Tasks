package cn.super12138.todo.logic.database

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import cn.super12138.todo.constants.Constants
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = Constants.DB_TABLE_NAME, indices = [androidx.room3.Index(value = ["sync_id"], unique = true)])
data class TaskEntity(
    @ColumnInfo(name = "content") val content: String,
    @ColumnInfo(name = "category") val category: String = "",
    @ColumnInfo(name = "completed") val isCompleted: Boolean = false,
    @ColumnInfo(name = "priority") val priority: Float,
    @ColumnInfo(name = "due_date") val dueDateMillis: Long? = null,
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Int = 0,
    @ColumnInfo(name = "created_at") val createdAtMillis: Long? = null,
    @ColumnInfo(name = "details", defaultValue = "''") val details: String = "",
    @ColumnInfo(name = "tags", defaultValue = "'[]'") val tags: List<String> = emptyList(),
    @ColumnInfo(name = "due_time") val dueTimeMinutes: Int? = null,
    @ColumnInfo(name = "subtasks", defaultValue = "'[]'") val subtasks: List<Subtask> = emptyList(),
    @ColumnInfo(name = "rollover", defaultValue = "0") val rollover: Boolean = false,
    @ColumnInfo(name = "recurrence") val recurrence: String? = null,
    @ColumnInfo(name = "recurrence_parent_id") val recurrenceParentId: Int? = null,
    @ColumnInfo(name = "sync_id", defaultValue = "''") val syncId: String = java.util.UUID.randomUUID().toString(),
    @ColumnInfo(name = "calendar_date", defaultValue = "''") val calendarDate: String = "",
    @ColumnInfo(name = "time_zone", defaultValue = "''") val timeZone: String = "",
    @ColumnInfo(name = "sync_parent", defaultValue = "''") val syncParent: String = "",
    @ColumnInfo(name = "sync_deleted", defaultValue = "0") val syncDeleted: Boolean = false,
    @ColumnInfo(name = "sync_retracted", defaultValue = "0") val syncRetracted: Boolean = false,
)

/** Old backups and navigation entries still carry their single category. */
val TaskEntity.taskTags: List<String>
    get() = tags.ifEmpty { listOf(category) }.filter { it.isNotBlank() }.distinct()
