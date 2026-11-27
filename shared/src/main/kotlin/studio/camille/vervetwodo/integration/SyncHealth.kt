package studio.camille.vervetwodo.integration

const val SYNC_FRESH_FOR_MS = 120_000L
const val SYNC_CHECK_INTERVAL_MS = 60_000L

enum class SyncHealth { CHECKING, DISCONNECTED, SYNCING, SYNCED, CONFLICT, OFFLINE, PENDING, UNCONFIRMED, STALE, ERROR }

/** Problems outrank activity, so a retry never paints an unresolved problem green. */
fun SyncReply.health(now: Long = System.currentTimeMillis()): SyncHealth = when {
    error != null && error != "offline" && !offline -> SyncHealth.ERROR
    state.conflicts().isNotEmpty() -> SyncHealth.CONFLICT
    (offline || error == "offline") && state.authenticated -> SyncHealth.OFFLINE
    !telemetry.checked -> SyncHealth.CHECKING
    !state.authenticated -> SyncHealth.DISCONNECTED
    offline -> SyncHealth.OFFLINE
    error != null -> SyncHealth.ERROR
    localChanges || state.pending > 0 -> SyncHealth.PENDING
    !telemetry.confirmed && telemetry.lastContactAt > 0 -> SyncHealth.UNCONFIRMED
    telemetry.confirmed && now - telemetry.lastSuccessAt !in 0..SYNC_FRESH_FOR_MS -> SyncHealth.STALE
    busy -> SyncHealth.SYNCING
    !telemetry.confirmed || state.pending < 0 -> SyncHealth.UNCONFIRMED
    else -> SyncHealth.SYNCED
}

fun SyncHealth.labelKey() = "syncStatus" + name.lowercase().replaceFirstChar { it.uppercase() }
fun SyncHealth.detailKey() = labelKey() + "Hint"
