package studio.camille.vervetwodo.integration

import java.util.UUID
import kotlinx.serialization.json.JsonElement

/** The helper's revision check makes journal writes and projections conditional on unchanged edits. */
class DesktopSync(private val client: HelperClient, private val source: SyncSource) {
    var status = SyncReply()
        private set

    private fun request(method: String, payload: JsonElement? = null) = client.exchange(
        HelperRequest(id = UUID.randomUUID().toString(), method = method, payload = payload))

    private fun engine(method: String, payload: String = "{}"): SyncReply {
        val result = request("engine.$method", protocolJson.parseToJsonElement(payload)).sync
            ?: throw HelperFailure("invalid_response")
        status = result
        if (method in setOf("connect", "apply")) result.error?.let { throw HelperFailure(it) }
        return result
    }

    fun connect(origin: String, code: String): WidgetSnapshot {
        engine("connect", protocolJson.encodeToString(SyncConnect(origin.trim(), code.trim())))
        return reconcile()
    }

    fun reconcile(requestSync: Boolean = true, checkOnly: Boolean = false): WidgetSnapshot {
        engine("state")
        if (!status.state.authenticated) return client.request("snapshot")
        repeat(200) {
            val local = request("syncRead")
            val snapshot = local.snapshot ?: throw HelperFailure("invalid_response")
            val journal = local.journal ?: SyncJournal()
            if (journal.outbox.isNotEmpty()) {
                val part = TaskSync.batch(journal.outbox)
                engine("apply", protocolJson.encodeToString(SyncBatch(part)))
                client.exchange(HelperRequest(id = UUID.randomUUID().toString(), method = "syncJournal",
                    expectedRevision = snapshot.revision, journal = journal.copy(outbox = journal.outbox.drop(part.size))))
                return@repeat
            }
            val planned = TaskSync.plan(journal, snapshot, source)
            if (planned.outbox.isNotEmpty()) {
                client.exchange(HelperRequest(id = UUID.randomUUID().toString(), method = "syncJournal",
                    expectedRevision = snapshot.revision, journal = planned))
                return@repeat
            }
            val records = engine("state").state.records
            val projection = TaskSync.project(records, snapshot)
            val result = if (projection != snapshot || journal.baseline != records) client.exchange(
                HelperRequest(id = UUID.randomUUID().toString(), method = "syncProject", expectedRevision = snapshot.revision,
                    journal = SyncJournal(records), snapshot = projection)).snapshot!! else snapshot
            if (requestSync && !status.busy) engine(if (checkOnly) "check" else "sync")
            return result
        }
        throw HelperFailure("sync_incomplete")
    }

    fun resolve(record: SyncRecord, field: String, value: String): WidgetSnapshot {
        reconcile()
        val local = request("syncRead")
        val journal = local.journal ?: SyncJournal()
        val latest = journal.baseline.find { it.id == record.id } ?: throw HelperFailure("task_changed")
        if (latest.fields[field] != record.fields[field]) throw HelperFailure("task_changed")
        val op = SyncOperation(record = record.id, kind = record.kind, field = field, value = value,
            replaces = latest.fields[field].orEmpty().map { it.id }, source = source)
        val baseline = TaskSync.predict(journal.baseline, listOf(op))
        client.exchange(HelperRequest(id = UUID.randomUUID().toString(), method = "syncProject",
            expectedRevision = local.snapshot!!.revision, journal = SyncJournal(baseline, listOf(op)),
            snapshot = TaskSync.project(baseline, local.snapshot!!)))
        return reconcile()
    }
}
