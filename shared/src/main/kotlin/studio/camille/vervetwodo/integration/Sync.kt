package studio.camille.vervetwodo.integration

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable data class SyncSource(val id: String, val name: String, val platform: String)
@Serializable data class SyncVersion(val id: String, val value: String, val author: String = "", val source: SyncSource? = null)
@Serializable data class SyncRecord(val id: String, val kind: String, val fields: Map<String, List<SyncVersion>> = emptyMap()) {
    fun value(field: String): String = fields[field]?.minByOrNull { it.id }?.value.orEmpty()
    fun conflicts(): Map<String, List<SyncVersion>> = fields.filterValues { versions -> versions.map { it.value }.distinct().size > 1 }
}
@Serializable data class SyncOperation(val id: String = UUID.randomUUID().toString(), val record: String,
    val kind: String, val field: String, val value: String, val replaces: List<String> = emptyList(), val source: SyncSource? = null)
@Serializable data class SyncState(val authenticated: Boolean = false, val user: String = "", val workspace: String = "",
    val origin: String = "", val pending: Int = 0, val records: List<SyncRecord> = emptyList())
@Serializable data class SyncTelemetry(val checked: Boolean = false, val confirmed: Boolean = false,
    val lastAttemptAt: Long = 0, val lastContactAt: Long = 0, val lastSuccessAt: Long = 0)
@Serializable data class SyncReply(val state: SyncState = SyncState(), val error: String? = null, val busy: Boolean = false,
    val telemetry: SyncTelemetry = SyncTelemetry(), val localChanges: Boolean = false, val offline: Boolean = false)
@Serializable data class SyncBatch(val operations: List<SyncOperation>)
@Serializable data class SyncConnect(val origin: String, val code: String)
@Serializable data class SyncJournal(val baseline: List<SyncRecord> = emptyList(), val outbox: List<SyncOperation> = emptyList())

/** Field versions are the edit's causal context; incoming data never becomes a wholesale backup replacement. */
object TaskSync {
    /** Respect the engine's signed batch bounds, including UTF-8 and JSON escaping. */
    fun batch(operations: List<SyncOperation>): List<SyncOperation> {
        val result = mutableListOf<SyncOperation>()
        var bytes = 256
        for (operation in operations.take(200)) {
            val size = protocolJson.encodeToString(operation).toByteArray(Charsets.UTF_8).size + 1
            require(size < 198000) { "invalidTask" }
            if (bytes + size >= 198000) break
            result.add(operation); bytes += size
        }
        return result
    }
    fun fields(task: WidgetTask): Map<String, String> = linkedMapOf(
        "title" to task.title, "details" to task.details, "completed" to task.completed.toString(),
        "priority" to task.priority.toString(), "dueDate" to task.dueDate, "dueTime" to task.dueTime,
        "timeZone" to task.timeZone, "checklist" to protocolJson.encodeToString(task.checklist),
        "recurrence" to protocolJson.encodeToString(task.recurrence), "tags" to protocolJson.encodeToString(task.tags),
        "parentId" to task.parentId, "deleted" to task.deleted.toString(), "retracted" to task.retracted.toString(),
        "createdAt" to task.createdAt.toString()).apply {
            task.rollover?.let { put("rollover", it.toString()) }
        }

    fun plan(journal: SyncJournal, snapshot: WidgetSnapshot, source: SyncSource): SyncJournal {
        require(journal.outbox.isEmpty())
        val prior = journal.baseline.associateBy { it.id }
        val desired = linkedMapOf<String, Pair<String, Map<String, String>>>()
        snapshot.tags.forEach { desired[it.id] = "tag" to linkedMapOf("name" to it.name, "color" to it.color, "icon" to it.icon, "deleted" to "false") }
        snapshot.tasks.forEach { desired[it.id] = "task" to fields(it) }
        // Only previously projected records can be deleted. A first connection unions local and remote data.
        journal.baseline.filter { it.id !in desired }.forEach { record ->
            desired[record.id] = record.kind to record.fields.mapValues { record.value(it.key) }.plus("deleted" to "true")
        }
        val operations = desired.flatMap { (id, record) -> record.second.mapNotNull { (field, value) ->
            val old = prior[id]
            if (old?.value(field) == value && field in old.fields) null
            else SyncOperation(record = id, kind = record.first, field = field, value = value,
                replaces = old?.fields?.get(field)?.map { it.id }.orEmpty(), source = source)
        } }
        return SyncJournal(predict(journal.baseline, operations), operations)
    }

    fun predict(records: List<SyncRecord>, operations: List<SyncOperation>): List<SyncRecord> {
        val result = records.associateBy { it.id }.toMutableMap()
        operations.forEach { operation ->
            val record = result[operation.record] ?: SyncRecord(operation.record, operation.kind)
            val versions = record.fields[operation.field].orEmpty().filter { it.id !in operation.replaces && it.id != operation.id } +
                SyncVersion(operation.id, operation.value, source = operation.source)
            result[record.id] = record.copy(fields = record.fields + (operation.field to versions))
        }
        return result.values.sortedBy { it.id }
    }

    fun project(records: List<SyncRecord>, previous: WidgetSnapshot): WidgetSnapshot {
        val tags = records.filter { it.kind == "tag" && it.value("deleted") != "true" }.map {
            TaskTag(it.id, it.value("name"), it.value("color").ifEmpty { "#3877B8" }, it.value("icon").ifEmpty { "tag" })
        }
        val old = previous.tasks.associateBy { it.id }
        val tasks = records.filter { it.kind == "task" }.map { record ->
            fun text(field: String) = record.value(field)
            val date = text("dueDate")
            val task = WidgetTask(record.id, text("title").ifEmpty { old[record.id]?.title.orEmpty() },
                completed = text("completed") == "true", details = text("details"),
                tags = protocolJson.decodeFromString<List<String>>(text("tags").ifEmpty { "[]" }),
                priority = text("priority").toIntOrNull() ?: 0, dueDate = date,
                dueTime = text("dueTime").takeIf { date.isNotEmpty() }.orEmpty(), timeZone = text("timeZone"),
                checklist = protocolJson.decodeFromString<List<ChecklistItem>>(text("checklist").ifEmpty { "[]" }),
                recurrence = protocolJson.decodeFromString<Recurrence?>(text("recurrence").ifEmpty { "null" }).takeIf { date.isNotEmpty() },
                createdAt = text("createdAt").toLongOrNull() ?: 0, deleted = text("deleted") == "true",
                retracted = text("retracted") == "true", parentId = text("parentId"),
                rollover = if ("rollover" in record.fields) text("rollover") == "true" else old[record.id]?.rollover)
            val prior = old[task.id]
            if (prior != null && fields(prior) == fields(task)) prior
            else task.copy(generation = (prior?.generation ?: 0) + 1, modifiedAt = System.currentTimeMillis())
        }
        return previous.copy(tasks = tasks, tags = tags)
    }
}
