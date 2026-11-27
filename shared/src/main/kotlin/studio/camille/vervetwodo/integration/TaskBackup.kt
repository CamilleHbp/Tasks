package studio.camille.vervetwodo.integration

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.util.UUID

@Serializable
data class TaskBackup(val format: String = "vervetwodo-desktop", val version: Int = 1,
                      val tasks: List<WidgetTask>, val tags: List<TaskTag>)

object Backups {
    fun encode(snapshot: WidgetSnapshot): String = protocolJson.encodeToString(TaskBackup(
        tasks = snapshot.tasks.filterNot { it.deleted }, tags = snapshot.tags))

    /** Imports are new records. Never replace a live replica or restore command identities. */
    fun import(snapshot: WidgetSnapshot, data: String): TaskCommand {
        require(data.toByteArray().size <= 3 * 1024 * 1024) { "invalidBackup" }
        val backup = protocolJson.decodeFromString<TaskBackup>(data)
        require(backup.format == "vervetwodo-desktop" && backup.version == 1) { "invalidBackup" }
        require(backup.tasks.map { it.id }.distinct().size == backup.tasks.size &&
            backup.tags.map { it.id }.distinct().size == backup.tags.size) { "invalidBackup" }
        val tags = snapshot.tags.toMutableList()
        val tagIDs = backup.tags.associate { tag ->
            require(tag.name.isNotBlank() && tag.color.matches(Regex("#[0-9A-Fa-f]{6}"))) { "invalidTag" }
            val existing = tags.find { it.name.equals(tag.name.trim(), ignoreCase = true) }
            val id = existing?.id ?: UUID.randomUUID().toString().also { tags += tag.copy(id = it, name = tag.name.trim()) }
            tag.id to id
        }
        val ids = backup.tasks.associate { it.id to UUID.randomUUID().toString() }
        val tasks = backup.tasks.filterNot { it.deleted }.map { task ->
            TaskRules.validate(task)
            require(task.tags.all { it in tagIDs }) { "invalidBackup" }
            task.copy(id = ids.getValue(task.id), tags = task.tags.map(tagIDs::getValue), parentId = "",
                generation = 0, retracted = false, createdAt = 0, modifiedAt = 0)
        }
        return TaskRules.transaction(snapshot, tasks, tags)
    }

    fun csv(snapshot: WidgetSnapshot): String {
        fun quote(value: String) = "\"" + value.replace("\"", "\"\"") + "\""
        val names = snapshot.tags.associate { it.id to it.name }
        return (listOf(listOf("Title", "Completed", "Notes", "Tags", "Priority", "Date", "Time", "Timezone")) +
            snapshot.tasks.filterNot { it.deleted }.map {
                listOf(it.title, it.completed.toString(), it.details, it.tags.mapNotNull(names::get).joinToString("; "),
                    it.priority.toString(), it.dueDate, it.dueTime, it.timeZone)
            }).joinToString("\r\n", postfix = "\r\n") { row -> row.joinToString(",", transform = ::quote) }
    }
}
