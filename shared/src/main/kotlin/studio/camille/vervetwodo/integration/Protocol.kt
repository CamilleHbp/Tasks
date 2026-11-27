package studio.camille.vervetwodo.integration

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val PROTOCOL_VERSION = 1

val protocolJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** No credentials or synchronization keys may be written to a widget snapshot. */
@Serializable
data class WidgetTask(
    val id: String,
    val title: String,
    val completed: Boolean = false,
    val details: String = "",
    val tags: List<String> = emptyList(),
    val priority: Int = 0,
    val dueDate: String = "",
    val dueTime: String = "",
    val timeZone: String = "",
    val checklist: List<ChecklistItem> = emptyList(),
    val recurrence: Recurrence? = null,
    val createdAt: Long = 0,
    val modifiedAt: Long = 0,
    val generation: Long = 0,
    val deleted: Boolean = false,
    val retracted: Boolean = false,
    val parentId: String = "",
    // null identifies older clients that never carried the per-task setting.
    val rollover: Boolean? = null,
)

@Serializable
data class ChecklistItem(val id: String, val title: String, val completed: Boolean = false)

@Serializable
data class Recurrence(val frequency: String, val interval: Int = 1, val anchor: String)

@Serializable
data class TaskTag(val id: String, val name: String, val color: String = "#3877B8", val icon: String = "tag")

@Serializable
data class WidgetSnapshot(
    val version: Int = PROTOCOL_VERSION,
    val revision: Long = 0,
    val locale: String = "en",
    val tasks: List<WidgetTask> = emptyList(),
    val tags: List<TaskTag> = emptyList(),
)

@Serializable
data class TaskCommand(
    val version: Int = PROTOCOL_VERSION,
    val id: String,
    val action: String,
    val taskId: String = "",
    val title: String = "",
    val completed: Boolean = false,
    val sourceId: String = "",
    val expectedRevision: Long? = null,
    val tasks: List<WidgetTask> = emptyList(),
    val tags: List<TaskTag>? = null,
)

@Serializable
data class HelperRequest(
    val version: Int = PROTOCOL_VERSION,
    val id: String,
    val method: String,
    val command: TaskCommand? = null,
    val locale: String? = null,
    val payload: kotlinx.serialization.json.JsonElement? = null,
    val expectedRevision: Long? = null,
    val journal: SyncJournal? = null,
    val snapshot: WidgetSnapshot? = null,
)

@Serializable
data class HelperResponse(
    val event: String? = null,
    val version: Int = PROTOCOL_VERSION,
    val id: String,
    val snapshot: WidgetSnapshot? = null,
    val error: String? = null,
    val journal: SyncJournal? = null,
    val sync: SyncReply? = null,
)
