package cn.super12138.todo.logic.database

import androidx.room3.Entity
import androidx.room3.PrimaryKey
import java.util.UUID

@Entity(tableName = "sync_state")
data class SyncEnvelope(
    @PrimaryKey val id: Int = 1,
    val revision: Long = 0,
    val journal: String = "{}",
    val tagsImported: Boolean = false,
    val sourceId: String = UUID.randomUUID().toString(),
)

@Entity(tableName = "sync_tags")
data class SyncedTag(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val color: Int,
    val deleted: Boolean = false,
    @androidx.room3.ColumnInfo(defaultValue = "'tag'") val icon: String = "tag",
)

data class LocalSyncSnapshot(val envelope: SyncEnvelope, val tasks: List<TaskEntity>, val tags: List<SyncedTag>)
