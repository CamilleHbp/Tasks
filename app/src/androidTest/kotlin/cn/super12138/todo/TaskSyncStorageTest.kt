package cn.super12138.todo

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.room3.Room
import cn.super12138.todo.logic.database.*
import cn.super12138.todo.logic.sync.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import studio.camille.vervetwodo.integration.*
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TaskSyncStorageTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @Test fun migrationPreservesIdsAndProjectionCannotOverwritePendingEdits() = runBlocking {
        val name = "sync-migration-${UUID.randomUUID()}"
        val path = context.getDatabasePath(name); path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            old.execSQL("CREATE TABLE todo (content TEXT NOT NULL, category TEXT NOT NULL, completed INTEGER NOT NULL, priority REAL NOT NULL, due_date INTEGER, id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, created_at INTEGER, details TEXT NOT NULL DEFAULT '', tags TEXT NOT NULL DEFAULT '[]', due_time INTEGER, subtasks TEXT NOT NULL DEFAULT '[]', recurrence TEXT, recurrence_parent_id INTEGER)")
            old.execSQL("INSERT INTO todo(content,category,completed,priority,id,details) VALUES('Existing Android task','Personal',0,2,123,'Keep my notes')")
            old.version = 9
        }
        val database = Room.databaseBuilder(context, TaskDatabase::class.java, name).addMigrations(TaskDatabase.MIGRATION_9_10, TaskDatabase.MIGRATION_10_11).build()
        try {
            val dao = database.taskDao()
            dao.importTagPreferences(listOf("Personal"), mapOf("Personal" to 0xff00aabb.toInt()))
            val migrated = dao.getTask(123)!!
            assertEquals("Keep my notes", migrated.details)
            assertTrue(migrated.syncId.isNotEmpty())
            assertEquals("tag", dao.syncTags().single().icon)
            dao.editSyncedTag("Personal", "Personal", 0xff00aabb.toInt(), "personal")
            val identity = dao.syncTags().single().id
            dao.editSyncedTag("Personal", "My life", 0xff00aabb.toInt())
            assertEquals(identity, dao.syncTags().single().id)
            assertEquals("personal", dao.syncTags().single().icon)
            val local = dao.localSyncSnapshot()
            val source = SyncSource(UUID.randomUUID().toString(), "Test Android", "android")
            val journal = TaskSync.plan(SyncJournal(), local.toWire(), source)
            assertTrue(dao.saveSyncJournal(local.envelope.revision, protocolJson.encodeToString(journal)))
            dao.update(dao.getTask(123)!!.copy(details = "Offline edit"))
            assertFalse(dao.applySyncProjection(local.envelope.revision, local.tasks, local.tags, "{}"))
            assertEquals("Offline edit", dao.getTask(123)!!.details)
            val updated = dao.localSyncSnapshot()
            val projected = updated.toWire().toAndroid(updated)
            assertEquals("personal", projected.second.single().icon)
            assertTrue(dao.applySyncProjection(updated.envelope.revision, projected.first, projected.second, updated.envelope.journal))
            assertEquals(123, dao.getTask(123)!!.id)
            dao.delete(dao.getTask(123)!!)
            assertNull(dao.getTask(123))
            assertTrue(dao.getTaskRow(123)!!.syncDeleted)
        } finally { database.close(); context.deleteDatabase(name) }
    }

    @Test fun nativeAdapterLoadsAndKeepsIndependentInstances() {
        val first = java.io.File(context.cacheDir, "sync-native-${UUID.randomUUID()}")
        val second = java.io.File(context.cacheDir, "sync-native-${UUID.randomUUID()}")
        try {
            val a = mobile.Mobile.newClient(first.absolutePath)
            val b = mobile.Mobile.newClient(second.absolutePath)
            try {
                assertFalse(protocolJson.decodeFromString<SyncReply>(a.call("state", "{}")).state.authenticated)
                assertFalse(protocolJson.decodeFromString<SyncReply>(b.call("state", "{}")).state.authenticated)
            } finally { a.close(); b.close() }
        } finally { first.deleteRecursively(); second.deleteRecursively() }
    }
}
