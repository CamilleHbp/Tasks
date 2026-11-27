package studio.camille.vervetwodo.integration

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HelperClientTest {
    @Test fun desktopCommandsSurviveHelperCrashAndReplay() {
        val directory = Files.createTempDirectory("helper-client")
        try {
            HelperClient(Path.of(System.getProperty("vervetwodo.test.helper")), directory).use { client ->
                val command = TaskCommand(id = UUID.randomUUID().toString(), action = "add", title = "Préparer demain")
                assertEquals(1, client.request("command", command).tasks.size)
                val children = ProcessHandle.current().children().use { stream ->
                    stream.filter { it.info().arguments().orElse(emptyArray()).contains(directory.toString()) }.toList()
                }
                assertEquals(1, children.size)
                children.single().destroyForcibly()
                children.single().onExit().get(5, TimeUnit.SECONDS)
                val recovered = client.request("command", command)
                assertEquals(1, recovered.tasks.size)
                assertEquals("Préparer demain", recovered.tasks.single().title)
                assertEquals(1L, recovered.revision)
                assertEquals("fr", client.request("locale", locale = "fr").locale)
            }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun concurrentCallersRetainAllCommands() {
        val directory = Files.createTempDirectory("helper-concurrent")
        val executor = Executors.newFixedThreadPool(4)
        try {
            HelperClient(Path.of(System.getProperty("vervetwodo.test.helper")), directory).use { client ->
                val requests = (1..12).map { index -> executor.submit {
                    client.request("command", TaskCommand(id = UUID.randomUUID().toString(), action = "add", title = "Task $index"))
                } }
                requests.forEach { it.get(15, TimeUnit.SECONDS) }
                assertEquals(12, client.request("snapshot").tasks.size)
            }
        } finally { executor.shutdownNow(); directory.toFile().deleteRecursively() }
    }
}
