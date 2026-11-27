package studio.camille.vervetwodo.integration

import org.junit.Assert.*
import org.junit.Test

class SyncHealthTest {
    private val now=500_000L
    private fun fresh()=SyncReply(SyncState(authenticated=true),telemetry=SyncTelemetry(checked=true,confirmed=true,lastSuccessAt=now))
    @Test fun greenRequiresFreshProofAndKnownZeroPending() {
        assertEquals(SyncHealth.SYNCED,fresh().health(now))
        assertEquals(SyncHealth.CHECKING,SyncReply().health(now))
        assertEquals(SyncHealth.DISCONNECTED,SyncReply(telemetry=SyncTelemetry(checked=true)).health(now))
        assertEquals(SyncHealth.UNCONFIRMED,fresh().copy(state=SyncState(authenticated=true,pending=-1)).health(now))
        assertEquals(SyncHealth.UNCONFIRMED,fresh().copy(telemetry=SyncTelemetry(checked=true,lastSuccessAt=now)).health(now))
        assertEquals(SyncHealth.STALE,fresh().health(now+SYNC_FRESH_FOR_MS+1))
        assertEquals(SyncHealth.STALE,fresh().health(now-1))
    }
    @Test fun problemsRemainVisibleDuringRetry() {
        assertEquals(SyncHealth.ERROR,fresh().copy(error="failed",busy=true).health(now))
        assertEquals(SyncHealth.OFFLINE,fresh().copy(error="failed",offline=true,busy=true).health(now))
        assertEquals(SyncHealth.OFFLINE,fresh().copy(error="offline",busy=true).health(now))
        assertEquals(SyncHealth.PENDING,fresh().copy(localChanges=true,busy=true).health(now))
        assertEquals(SyncHealth.SYNCING,fresh().copy(busy=true).health(now))
        assertEquals(SyncHealth.STALE,fresh().copy(busy=true).health(now+SYNC_FRESH_FOR_MS+1))
        assertEquals(SyncHealth.UNCONFIRMED,fresh().copy(busy=true,telemetry=SyncTelemetry(checked=true,lastContactAt=now)).health(now))
        val record=SyncRecord("1","task",mapOf("title" to listOf(SyncVersion("a","A"),SyncVersion("b","B"))))
        assertEquals(SyncHealth.CONFLICT,fresh().copy(state=SyncState(authenticated=true,records=listOf(record)),busy=true).health(now))
    }
}
