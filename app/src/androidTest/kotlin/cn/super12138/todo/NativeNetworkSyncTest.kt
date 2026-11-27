package cn.super12138.todo

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import studio.camille.vervetwodo.integration.*
import java.util.UUID

/** Invoked by scripts/test-sync-android-desktop.py against an isolated local server. */
class NativeNetworkSyncTest {
    @Test fun androidAndDesktopExchangeChanges() {
        val arguments = InstrumentationRegistry.getArguments()
        val origin = arguments.getString("syncOrigin")
        assumeTrue(origin != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = java.io.File(context.cacheDir, "network-sync-${UUID.randomUUID()}")
        val client = mobile.Mobile.newClient(directory.absolutePath)
        val id = arguments.getString("syncRecord")!!
        val native = arguments.getString("native") == "true"
        val updates = java.util.concurrent.Semaphore(0)
        client.startUpdates(object : mobile.UpdateListener { override fun onChange() { updates.release() } })
        fun call(method: String, payload: String = "{}"): SyncState {
            val reply = protocolJson.decodeFromString<SyncReply>(client.call(method, payload))
            assertNull(reply.error)
            return reply.state
        }
        try {
            call("connect", protocolJson.encodeToString(SyncConnect(origin!!, arguments.getString("syncCode")!!)))
            call("apply", protocolJson.encodeToString(SyncBatch(listOf(SyncOperation(record = id, kind = "task", field = "title", value = "From Android")))))
            call("sync")
            val deadline = System.nanoTime() + 30_000_000_000
            while (call(if (native) "state" else "sync").records.single { it.id == id }.value("details") != "From desktop") {
                check(System.nanoTime() < deadline && updates.tryAcquire(30, java.util.concurrent.TimeUnit.SECONDS)) { "Desktop change was not received" }
            }
            call("apply", protocolJson.encodeToString(SyncBatch(listOf(SyncOperation(record = id, kind = "task", field = "completed", value = "true")))))
            val afterSync = call("sync")
            if (!native) assertEquals(0, afterSync.pending)
            // Native propagation is asynchronous; only the server checkpoint can clear pending.
            val confirmationDeadline = System.nanoTime() + 30_000_000_000
            while (true) {
                val reply = protocolJson.decodeFromString<SyncReply>(client.call("check", "{}"))
                assertNull(reply.error)
                if (reply.telemetry.confirmed) { assertEquals(0, reply.state.pending); break }
                check(System.nanoTime() < confirmationDeadline) { "Server did not confirm Android changes" }
                Thread.sleep(250)
            }
            if (native) {
                while (call("state").records.single { it.id == id }.value("details") != "Desktop observed completion") {
                    check(updates.tryAcquire(30, java.util.concurrent.TimeUnit.SECONDS)) { "Desktop did not observe completion" }
                }
            }
        } finally { client.close(); directory.deleteRecursively() }
    }
}
