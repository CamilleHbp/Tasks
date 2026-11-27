package cn.super12138.todo.logic.sync

import kotlinx.serialization.encodeToString
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import cn.super12138.todo.constants.Constants
import cn.super12138.todo.logic.database.*
import studio.camille.vervetwodo.integration.*
import java.io.InputStream
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry

class TaskImports(private val dao: TaskDao, private val tags: SyncedTags) {
    suspend fun export(output: java.io.OutputStream) {
        tags.initialize()
        val snapshot = dao.localSyncSnapshot()
        val data = Backups.encode(snapshot.toWire()).toByteArray(Charsets.UTF_8)
        val rollover = protocolJson.encodeToString(snapshot.tasks.filterNot { it.syncDeleted }.map { it.rollover })
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("tasks.json")); zip.write(data); zip.closeEntry()
            zip.putNextEntry(ZipEntry("rollover.json")); zip.write(rollover.toByteArray(Charsets.UTF_8)); zip.closeEntry()
        }
    }

    suspend fun import(input: InputStream, context: Context) {
        val directory = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "task-import-").toFile()
        try {
            val allowed = setOf("tasks.json", "rollover.json", Constants.DB_NAME, Constants.DB_NAME + "-wal", Constants.DB_NAME + "-shm", Constants.SP_NAME + ".preferences_pb")
            val seen = mutableSetOf<String>()
            var total = 0L
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(entry.name in allowed && seen.add(entry.name) && !entry.isDirectory)
                    java.io.File(directory, entry.name).outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        while (true) { val size = zip.read(buffer); if (size < 0) break
                            total += size; require(total <= 64 * 1024 * 1024); output.write(buffer, 0, size) }
                    }
                }
            }
            val json = java.io.File(directory, "tasks.json")
            val data = if (json.exists()) { require(json.length() <= 3 * 1024 * 1024); json.readText() }
                else legacy(java.io.File(directory, Constants.DB_NAME))
            // Validate everything before the transaction changes the application store.
            Backups.import(WidgetSnapshot(), data)
            tags.initialize()
            val rolloverFile = java.io.File(directory, "rollover.json")
            val rollover = if (rolloverFile.exists()) protocolJson.decodeFromString<List<Boolean>>(rolloverFile.readText()) else emptyList()
            dao.importTaskBackup(data, rollover)
            tags.refreshWidgetCache()
            (context.applicationContext as? cn.super12138.todo.VerveDoApp)?.refreshTaskWidgets()
        } finally { directory.deleteRecursively() }
    }

    private fun legacy(file: java.io.File): String {
        require(file.isFile)
        val tasks = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            database.rawQuery("SELECT * FROM todo", null).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        require(size < 10000)
                        fun text(name: String, default: String = ""): String { val i = cursor.getColumnIndex(name); return if (i < 0 || cursor.isNull(i)) default else cursor.getString(i) }
                        fun number(name: String): Long? = text(name).toLongOrNull()
                        val recurrence = text("recurrence").takeIf { it.isNotEmpty() }
                        require(recurrence == null || cn.super12138.todo.logic.TaskRecurrence.decode(recurrence) != null)
                        add(TaskEntity(content = text("content"), category = text("category"), isCompleted = number("completed") == 1L,
                            priority = text("priority", "0").toFloat(), id = number("id")?.toInt() ?: 0, dueDateMillis = number("due_date"),
                            createdAtMillis = number("created_at"), details = text("details"),
                            tags = protocolJson.decodeFromString(text("tags", "[]")), dueTimeMinutes = number("due_time")?.toInt(),
                            subtasks = protocolJson.decodeFromString(text("subtasks", "[]")), recurrence = recurrence))
                    }
                }
            }
        }
        val importedTags = tasks.flatMap { it.taskTags }.distinct().map { SyncedTag(name = it, color = 0xff3877b8.toInt()) }
        return Backups.encode(LocalSyncSnapshot(SyncEnvelope(), tasks, importedTags).toWire())
    }
}
