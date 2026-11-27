package studio.camille.vervetwodo.integration

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.net.ServerSocket
import java.util.UUID

class DesktopSyncTest {
    @Test fun devicesReconnectAfterOfflineEditsAndHelperRestart() {
        val root = Files.createTempDirectory("verve-sync-test")
        val serverData = Files.createDirectory(root.resolve("server"))
        val port = ServerSocket(0).use { it.localPort }
        val origin = "http://127.0.0.1:$port"
        fun server() = ProcessBuilder(System.getProperty("vervetwodo.test.server")).apply {
            environment()["DATA_DIR"] = serverData.toString(); environment()["LISTEN_ADDR"] = "127.0.0.1:$port"
            redirectError(root.resolve("server.log").toFile())
        }.start()
        var process = server()
        fun waitFor(block: () -> Boolean) { val deadline = System.nanoTime() + 20_000_000_000; while (!block()) { check(System.nanoTime() < deadline) { "Timed out waiting for synchronization" }; Thread.sleep(50) } }
        fun ready() { waitFor { runCatching { java.net.URI("$origin/healthz").toURL().openStream().use { it.readBytes() }; true }.getOrDefault(false) } }
        val helper = Path.of(System.getProperty("vervetwodo.test.helper"))
        val aDir = Files.createDirectory(root.resolve("a")); val bDir = Files.createDirectory(root.resolve("b"))
        var a = HelperClient(helper, aDir); val b = HelperClient(helper, bDir)
        val sourceA = SyncSource(UUID.randomUUID().toString(), "A", "macos")
        var syncA = DesktopSync(a, sourceA)
        val syncB = DesktopSync(b, SyncSource(UUID.randomUUID().toString(), "B", "macos"))
        try {
            ready()
            val code = protocolJson.decodeFromString<String>(Files.readString(serverData.resolve("access-code.json")))
            val task = WidgetTask(UUID.randomUUID().toString(), "Existing local task", dueDate = "2026-10-05", details = "Before sync")
            val before = a.request("snapshot")
            a.request("command", TaskRules.transaction(before, listOf(task)))
            syncA.connect(origin, code); syncB.connect(origin, code)
            waitFor { syncA.reconcile(); syncB.reconcile().tasks.any { it.id == task.id } }
            val snapB = b.request("snapshot")
            b.request("command", TaskRules.transaction(snapB, listOf(snapB.tasks.single().copy(details = "From B"))))
            waitFor { syncB.reconcile(); syncA.reconcile().tasks.single().details == "From B" }
            // A transport failure changes no task records but must still notify the UI.
            waitFor { syncA.reconcile(requestSync = false); !syncA.status.busy }
            process.destroy(); process.waitFor()
            val failureNotice = java.util.concurrent.CountDownLatch(1)
            a.onSyncChange = { failureNotice.countDown() }
            syncA.reconcile()
            assertTrue("Sync failure was not announced", failureNotice.await(20, java.util.concurrent.TimeUnit.SECONDS))
            waitFor { syncA.reconcile(requestSync = false); syncA.status.error != null }
            val failure = syncA.status.error
            syncA.reconcile(requestSync = false)
            assertEquals("Reading state must retain the failure", failure, syncA.status.error)
            a.onSyncChange = null
            val offline = a.request("snapshot")
            a.request("command", TaskRules.transaction(offline, listOf(offline.tasks.single().copy(title = "Edited offline"))))
            syncA.reconcile()
            a.close()
            a = HelperClient(helper, aDir); syncA = DesktopSync(a, sourceA)
            process = server(); ready()
            waitFor { syncA.reconcile(); syncB.reconcile().tasks.single().title == "Edited offline" }
            assertEquals("From B", b.request("snapshot").tasks.single().details)
            assertEquals(1, b.request("snapshot").tasks.size)
            waitFor { syncA.reconcile(requestSync = false); !syncA.status.busy && syncA.status.telemetry.confirmed }
            assertTrue(syncA.status.telemetry.lastSuccessAt > 0)
            assertEquals(SyncHealth.SYNCED, syncA.status.health())
            val confirmedAt = syncA.status.telemetry.lastSuccessAt
            a.close(); a = HelperClient(helper, aDir); syncA = DesktopSync(a, sourceA)
            syncA.reconcile(requestSync = false)
            assertFalse("Restart must verify again", syncA.status.telemetry.confirmed)
            assertEquals(confirmedAt, syncA.status.telemetry.lastSuccessAt)
        } finally { a.close(); b.close(); process.destroy(); process.waitFor(); root.toFile().deleteRecursively() }
    }
}
