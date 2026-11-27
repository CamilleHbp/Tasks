package cn.super12138.todo.logic.sync

import android.content.Context
import cn.super12138.todo.VerveDoApp
import cn.super12138.todo.logic.database.TaskDao
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import studio.camille.vervetwodo.integration.*

private class SyncFailure(val code: String) : Exception(code)
typealias SyncStatus = SyncReply

/** Room edits and their revision marker commit together. Causal operations are
 * journaled before Go accepts them; projections require an unchanged revision. */
class SyncRepository(private val context: Context, private val dao: TaskDao, private val tags: SyncedTags) {
    private val work = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val native by lazy { mobile.Mobile.newClient(java.io.File(context.noBackupFilesDir, "sync-v1").absolutePath) }
    private val mutable = MutableStateFlow(SyncStatus())
    val status = mutable.asStateFlow()
    private var started = false
    @Volatile private var foreground = false

    fun onForeground() {
        foreground = true
        mutable.update { it.copy(telemetry = it.telemetry.copy(checked = false, confirmed = false)) }
        scope.launch { synchronize() }
    }
    fun onBackground() { foreground = false }

    @OptIn(FlowPreview::class)
    fun start() {
        if (started) return
        started = true
        scope.launch { dao.observeSyncRevision().distinctUntilChanged().onEach { mutable.update { it.copy(localChanges = true) } }.debounce(500).collect { synchronize() } }
        scope.launch {
            native.startUpdates(object : mobile.UpdateListener {
                override fun onChange() { scope.launch { synchronize() } }
            })
            val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
            mutable.update { it.copy(offline = connectivity.activeNetwork == null) }
            connectivity.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    mutable.update { it.copy(offline = false, telemetry = it.telemetry.copy(checked = false, confirmed = false)) }
                    scope.launch { synchronize() }
                }
                override fun onLost(network: android.net.Network) {
                    mutable.update { it.copy(offline = connectivity.activeNetwork == null, telemetry = it.telemetry.copy(checked = false, confirmed = false)) }
                }
            })
        }
        scope.launch {
            while (isActive) {
                delay(SYNC_CHECK_INTERVAL_MS)
                if (foreground && mutable.value.state.authenticated) synchronize(reconnect = false)
            }
        }
    }

    suspend fun connect(origin: String, code: String) = operation {
        tags.initialize()
        call("connect", protocolJson.encodeToString(SyncConnect(origin.trim(), code.trim())))
        call("sync"); reconcile(); call("sync"); reconcile()
    }

    suspend fun synchronize(reconnect: Boolean = true) = operation {
        val current = call("state")
        if (!current.authenticated) return@operation
        tags.initialize()
        if (current.records.isEmpty()) call("sync")
        reconcile(); call(if (reconnect) "sync" else "check"); reconcile()
    }

    suspend fun resolve(record: SyncRecord, field: String, value: String) = operation {
        reconcile()
        val local = dao.localSyncSnapshot()
        val journal = protocolJson.decodeFromString<SyncJournal>(local.envelope.journal)
        val latest = journal.baseline.find { it.id == record.id } ?: throw SyncFailure("sync_failed")
        if (latest.fields[field] != record.fields[field]) throw SyncFailure("task_changed")
        val edit = SyncOperation(record = record.id, kind = record.kind, field = field, value = value,
            replaces = latest.fields[field].orEmpty().map { it.id }, source = source(local.envelope.sourceId))
        val next = SyncJournal(TaskSync.predict(journal.baseline, listOf(edit)), listOf(edit))
        val (tasks, tagRecords) = TaskSync.project(next.baseline, local.toWire()).toAndroid(local)
        if (!dao.applySyncProjection(local.envelope.revision, tasks, tagRecords, protocolJson.encodeToString(next))) throw SyncFailure("task_changed")
        reconcile(); call("sync"); reconcile()
    }

    private fun source(id: String) = SyncSource(id, android.os.Build.MODEL.take(64).ifBlank { "Android" }, "android")
    private fun call(method: String, payload: String = "{}"): SyncState {
        val response = protocolJson.decodeFromString<SyncReply>(native.call(method, payload))
        mutable.update { it.copy(state = response.state, telemetry = response.telemetry,
            error = if (method in setOf("sync", "check", "connect") && response.error == null) null else it.error) }
        response.error?.let { throw SyncFailure(it) }
        return response.state
    }

    private suspend fun reconcile() {
        repeat(100) {
            val local = dao.localSyncSnapshot()
            val journal = protocolJson.decodeFromString<SyncJournal>(local.envelope.journal)
            if (journal.outbox.isNotEmpty()) {
                val part = TaskSync.batch(journal.outbox)
                call("apply", protocolJson.encodeToString(SyncBatch(part)))
                dao.saveSyncJournal(local.envelope.revision, protocolJson.encodeToString(journal.copy(outbox = journal.outbox.drop(part.size))))
                return@repeat
            }
            val planned = TaskSync.plan(journal, local.toWire(), source(local.envelope.sourceId))
            if (planned.outbox.isNotEmpty()) {
                dao.saveSyncJournal(local.envelope.revision, protocolJson.encodeToString(planned))
                return@repeat
            }
            val state = call("state")
            if (state.records == journal.baseline) return
            val projection = TaskSync.project(state.records, local.toWire())
            val (tasks, tagRecords) = projection.toAndroid(local)
            if (dao.applySyncProjection(local.envelope.revision, tasks, tagRecords, protocolJson.encodeToString(SyncJournal(state.records)))) {
                tags.refreshWidgetCache()
                (context.applicationContext as? VerveDoApp)?.refreshTaskWidgets()
                return
            }
        }
        throw SyncFailure("sync_incomplete")
    }

    private suspend fun operation(block: suspend () -> Unit): Boolean = withContext(Dispatchers.IO) {
        work.withLock {
            mutable.value = mutable.value.copy(busy = true)
            try {
                block()
                val local = dao.localSyncSnapshot()
                val journal = protocolJson.decodeFromString<SyncJournal>(local.envelope.journal)
                val dirty = journal.outbox.isNotEmpty() || TaskSync.plan(journal, local.toWire(), source(local.envelope.sourceId)).outbox.isNotEmpty()
                mutable.update { it.copy(localChanges = dirty) }
                true
            }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutable.value = mutable.value.copy(error = (error as? SyncFailure)?.code ?: "sync_failed"); false }
            finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }
}
