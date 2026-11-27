package studio.camille.vervetwodo.integration

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

data class SyncConflict(val record: SyncRecord, val field: String, val versions: List<SyncVersion>)

/** Include deleted records: a delete/edit disagreement must remain resolvable. */
fun SyncState.conflicts(): List<SyncConflict> = records.sortedBy { it.id }.flatMap { record ->
    record.conflicts().toSortedMap().map { (field, versions) -> SyncConflict(record, field, versions) }
}

/** Never truncate the alternatives the user is about to discard. Unknown values stay readable verbatim. */
fun SyncState.conflictValue(field: String, value: String, text: (String) -> String): String {
    if (value.isEmpty()) return text("syncEmpty")
    return runCatching {
        when (field) {
            "completed" -> text(if (value == "true") "syncCompleted" else "syncTodo")
            "deleted", "retracted" -> text(if (value == "true") "syncDeleted" else "syncKept")
            "rollover" -> text(if (value == "true") "syncEnabled" else "syncDisabled")
            "priority" -> text("syncPriority" + value)
            "tags" -> protocolJson.decodeFromString<List<String>>(value).joinToString(", ") { id ->
                records.find { it.id == id }?.value("name")?.takeIf { it.isNotBlank() } ?: id
            }.ifEmpty { text("syncEmpty") }
            "checklist" -> protocolJson.decodeFromString<List<ChecklistItem>>(value).joinToString("\n") {
                "${text(if (it.completed) "syncCompleted" else "syncTodo")}: ${it.title}"
            }.ifEmpty { text("syncEmpty") }
            "recurrence" -> protocolJson.decodeFromString<Recurrence?>(value)?.let {
                "${it.interval} ${text("syncFrequency_" + it.frequency)} · ${it.anchor}"
            } ?: text("syncDisabled")
            "parentId" -> records.find { it.id == value }?.value("title")?.takeIf { it.isNotBlank() } ?: value
            "createdAt" -> DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(Locale.getDefault())
                .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(value.toLong()))
            else -> value
        }
    }.getOrDefault(value)
}
