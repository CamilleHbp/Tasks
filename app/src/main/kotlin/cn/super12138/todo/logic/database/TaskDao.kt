package cn.super12138.todo.logic.database
import cn.super12138.todo.logic.sync.toWire
import cn.super12138.todo.logic.sync.toAndroid

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Update
import androidx.room3.Transaction
import cn.super12138.todo.constants.Constants
import cn.super12138.todo.logic.rolledForward
import cn.super12138.todo.logic.nextOccurrence
import cn.super12138.todo.logic.BulkTaskEdit
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Transaction
    suspend fun rollForward(today: java.time.LocalDate = java.time.LocalDate.now()): Boolean {
        var changed = false
        getTasksForTagUpdate().forEach { task ->
            val next = task.rolledForward(today)
            if (next != task) { update(next); changed = true }
        }
        return changed
    }

    @Query("SELECT COUNT(*) FROM ${Constants.DB_TABLE_NAME} WHERE sync_deleted = 0")
    suspend fun count(): Int

    @Transaction
    suspend fun insertIfEmpty(tasks: List<TaskEntity>): Boolean {
        if (count() != 0) return false
        tasks.forEach { insert(it) }
        return true
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRow(task: TaskEntity): Long

    @Transaction
    suspend fun insert(task: TaskEntity): Long {
        val previous = if (task.id != 0) getTaskRow(task.id) else null
        val normalized = cn.super12138.todo.logic.sync.normalizeLocalTask(task, previous)
        val id = insertRow(normalized)
        ensureTaskTags(normalized.taskTags)
        recordLocalChange()
        return id
    }

    @Query("SELECT * FROM ${Constants.DB_TABLE_NAME} WHERE recurrence_parent_id = :taskId AND sync_deleted = 0 LIMIT 1")
    suspend fun nextFor(taskId: Int): TaskEntity?

    @Transaction
    suspend fun saveWithRecurrence(task: TaskEntity) {
        val previous = if (task.id != 0) getTaskRow(task.id) else null
        val id = insert(task).toInt()
        val saved = getTask(id) ?: return
        if (saved.isCompleted && previous?.isCompleted != true) createNext(saved)
        if (!saved.isCompleted && previous?.isCompleted == true) removeUntouchedNext(previous)
    }

    suspend fun createNext(task: TaskEntity) {
        task.nextOccurrence()?.let { occurrence ->
            val existing = getSyncTasks().find { it.syncId == occurrence.syncId }
            if (existing == null || existing.syncRetracted) {
                insert(occurrence.copy(id = existing?.id ?: 0, createdAtMillis = existing?.createdAtMillis ?: task.createdAtMillis))
            }
        }
    }

    suspend fun removeUntouchedNext(task: TaskEntity) {
        val next = nextFor(task.id) ?: return
        // Undo removes only an untouched generated occurrence; subsequent edits are preserved.
        if (next.copy(id = 0, createdAtMillis = null) == task.nextOccurrence()) update(next.copy(syncDeleted = true, syncRetracted = true))
    }

    @Transaction
    suspend fun editTasks(ids: Set<Int>, edit: BulkTaskEdit) {
        ids.forEach { id -> getTask(id)?.let { update(edit.applyTo(it)) } }
    }

    @Query("SELECT * FROM ${Constants.DB_TABLE_NAME} WHERE sync_deleted = 0")
    fun getAll(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM ${Constants.DB_TABLE_NAME} WHERE id = :taskId AND sync_deleted = 0")
    fun observeTask(taskId: Int): Flow<TaskEntity?>

    @Query("SELECT * FROM ${Constants.DB_TABLE_NAME} WHERE id = :taskId AND sync_deleted = 0")
    suspend fun getTask(taskId: Int): TaskEntity?

    @Query("SELECT * FROM ${Constants.DB_TABLE_NAME} WHERE sync_deleted = 0")
    suspend fun getTasksForTagUpdate(): List<TaskEntity>

    @Query("UPDATE ${Constants.DB_TABLE_NAME} SET tags = :tags, category = :category WHERE id = :taskId AND sync_deleted = 0")
    suspend fun updateTagsRow(taskId: Int, tags: List<String>, category: String)

    @Transaction
    suspend fun updateTags(taskId: Int, tags: List<String>, category: String) {
        updateTagsRow(taskId, tags, category); ensureTaskTags(tags); recordLocalChange()
    }

    @Transaction
    suspend fun replaceTag(old: String, replacement: String?) {
        getTasksForTagUpdate().forEach { task ->
            if (old in task.taskTags) {
                val tags = cn.super12138.todo.logic.replaceTag(task.taskTags, old, replacement)
                updateTags(task.id, tags, tags.firstOrNull().orEmpty())
            }
        }
    }

    @Query("UPDATE ${Constants.DB_TABLE_NAME} SET subtasks = :subtasks WHERE id = :taskId AND sync_deleted = 0")
    suspend fun updateSubtasksRow(taskId: Int, subtasks: List<Subtask>)

    @Transaction
    suspend fun updateSubtasks(taskId: Int, subtasks: List<Subtask>) {
        updateSubtasksRow(taskId, subtasks); recordLocalChange()
    }

    @Transaction
    suspend fun setSubtaskCompleted(taskId: Int, subtaskId: String, completed: Boolean) {
        val task = getTask(taskId) ?: return
        updateSubtasks(taskId, task.subtasks.map {
            if (it.id == subtaskId) it.copy(isCompleted = completed) else it
        })
    }

    @Query("SELECT DISTINCT category FROM ${Constants.DB_TABLE_NAME} WHERE TRIM(category) != ''")
    fun getCategories(): Flow<List<String>>

    @Update
    suspend fun updateRow(task: TaskEntity)

    @Transaction
    suspend fun update(task: TaskEntity) {
        val normalized = cn.super12138.todo.logic.sync.normalizeLocalTask(task, getTaskRow(task.id))
        updateRow(normalized); ensureTaskTags(normalized.taskTags); recordLocalChange()
    }

    @Transaction
    suspend fun completeTask(taskId: Int) {
        val task = getTask(taskId) ?: return
        if (task.isCompleted) return
        update(task.copy(isCompleted = true))
        createNext(task)
    }

    @Transaction
    suspend fun restoreTask(taskId: Int) {
        val task = getTask(taskId) ?: return
        if (!task.isCompleted) return
        removeUntouchedNext(task)
        update(task.copy(isCompleted = false))
    }

    @Delete
    suspend fun deleteRow(task: TaskEntity)

    @Transaction
    suspend fun delete(task: TaskEntity) { updateRow(task.copy(syncDeleted = true)); recordLocalChange() }

    @Query("UPDATE ${Constants.DB_TABLE_NAME} SET sync_deleted = 1 WHERE id in (:taskIds)")
    suspend fun deleteRowsFromIds(taskIds: Set<Int>)

    @Transaction
    suspend fun deleteFromIds(taskIds: Set<Int>) { deleteRowsFromIds(taskIds); recordLocalChange() }


    @Query("SELECT * FROM todo WHERE id = :taskId")
    suspend fun getTaskRow(taskId: Int): TaskEntity?

    @Query("SELECT * FROM todo")
    suspend fun getSyncTasks(): List<TaskEntity>

    @Query("SELECT revision FROM sync_state WHERE id = 1")
    fun observeSyncRevision(): Flow<Long?>

    @Query("SELECT * FROM sync_state WHERE id = 1")
    suspend fun syncEnvelope(): SyncEnvelope?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSyncEnvelope(envelope: SyncEnvelope)

    @Query("SELECT * FROM sync_tags")
    suspend fun syncTags(): List<SyncedTag>

    @Query("SELECT * FROM sync_tags WHERE deleted = 0")
    fun observeSyncTags(): Flow<List<SyncedTag>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSyncTagRow(tag: SyncedTag)

    @Transaction
    suspend fun recordLocalChange() {
        val current = syncEnvelope() ?: SyncEnvelope()
        saveSyncEnvelope(current.copy(revision = current.revision + 1))
    }

    suspend fun ensureTaskTags(names: List<String>) {
        val existing = syncTags()
        val colors = cn.super12138.todo.logic.assignTagColors(names, existing.associate { it.name to it.color })
        names.filter { name -> existing.none { it.name == name && !it.deleted } }.forEach { name ->
            saveSyncTagRow(SyncedTag(name = name, color = colors.getValue(name)))
        }
    }

    @Transaction
    suspend fun importTagPreferences(names: List<String>, colors: Map<String, Int>) {
        val envelope = syncEnvelope() ?: SyncEnvelope()
        if (envelope.tagsImported) return
        val tasks = getTasksForTagUpdate()
        val allNames = (names + tasks.flatMap { it.taskTags }).filter { it.isNotBlank() }.distinct()
        val assigned = cn.super12138.todo.logic.assignTagColors(allNames, colors)
        val existing = syncTags()
        allNames.forEach { name ->
            val prior = existing.find { it.name == name && !it.deleted }
            saveSyncTagRow((prior ?: SyncedTag(name = name, color = assigned.getValue(name))).copy(color = assigned.getValue(name)))
        }
        cn.super12138.todo.logic.sync.initializeTaskIdentities(tasks).forEach { updateRow(it) }
        saveSyncEnvelope(envelope.copy(tagsImported = true, revision = envelope.revision + 1))
    }

    @Transaction
    suspend fun editSyncedTag(old: String?, replacement: String?, color: Int?, icon: String? = null) {
        val tags = syncTags()
        val existing = tags.find { it.name == old && !it.deleted }
        if (replacement != null) {
            require(tags.none { !it.deleted && it.id != existing?.id && it.name.equals(replacement, ignoreCase = true) })
            val resolvedColor = color ?: existing?.color ?: 0xff3877b8.toInt()
            saveSyncTagRow((existing ?: SyncedTag(name = replacement, color = resolvedColor)).copy(name = replacement, color = resolvedColor, deleted = false, icon = icon ?: existing?.icon ?: "tag"))
        } else if (existing != null) saveSyncTagRow(existing.copy(deleted = true))
        if (old != null && old != replacement) replaceTag(old, replacement)
        recordLocalChange()
    }

    @Transaction
    suspend fun localSyncSnapshot(): LocalSyncSnapshot = LocalSyncSnapshot(syncEnvelope() ?: SyncEnvelope(), getSyncTasks(), syncTags())

    @Transaction
    suspend fun importTaskBackup(data: String, rollover: List<Boolean> = emptyList()) {
        val previous = localSyncSnapshot()
        val snapshot = previous.toWire()
        val command = studio.camille.vervetwodo.integration.Backups.import(snapshot, data)
        val imported = snapshot.copy(tasks = command.tasks, tags = command.tags ?: snapshot.tags)
        val (tasks, tags) = imported.toAndroid(previous)
        require(rollover.isEmpty() || rollover.size == tasks.size)
        tasks.forEachIndexed { index, task -> insert(task.copy(rollover = rollover.getOrElse(index) { task.rollover })) }
        tags.forEach { saveSyncTagRow(it) }
        recordLocalChange()
    }

    @Transaction
    suspend fun saveSyncJournal(expectedRevision: Long, journal: String): Boolean {
        val current = syncEnvelope() ?: SyncEnvelope()
        if (current.revision != expectedRevision) return false
        saveSyncEnvelope(current.copy(journal = journal))
        return true
    }

    @Transaction
    suspend fun applySyncProjection(expectedRevision: Long, tasks: List<TaskEntity>, tags: List<SyncedTag>, journal: String): Boolean {
        val current = syncEnvelope() ?: SyncEnvelope()
        if (current.revision != expectedRevision) return false
        val ids = getSyncTasks().associate { it.syncId to it.id }.toMutableMap()
        tasks.forEach { ids[it.syncId] = insertRow(it).toInt() }
        tasks.forEach { task ->
            val id = ids.getValue(task.syncId)
            val parent = ids[task.syncParent]
            updateRow(task.copy(id = id, recurrenceParentId = parent))
        }
        val activeTags = tags.map { it.id }.toSet()
        syncTags().filter { it.id !in activeTags }.forEach { saveSyncTagRow(it.copy(deleted = true)) }
        tags.forEach { saveSyncTagRow(it) }
        saveSyncEnvelope(current.copy(journal = journal))
        return true
    }

    /*@Query("DELETE FROM todo")
    suspend fun deleteAllTodo()*/
}
