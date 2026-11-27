package studio.camille.vervetwodo.integration

import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class WidgetFilesTest {
    @Test fun nativeActivationRetainsTaskAndQueuesForResidentApp() {
        val directory = Files.createTempDirectory("widget-activation")
        try {
            val command = WidgetFiles.activation("vervetwodo-preview://task?id=task-identity-0001")!!
            WidgetFiles.enqueue(directory, command)
            assertEquals(command, WidgetFiles.readCommand(WidgetFiles.commands(directory).single()))
            assertEquals("task-identity-0001", command.taskId)
            assertEquals("add", WidgetFiles.activation("vervetwodo-preview://add")!!.action)
            assertNull(WidgetFiles.activation("https://task?id=task-identity-0001"))
            assertNull(WidgetFiles.activation("vervetwodo-preview://task?id=%zz"))
            assertNull(WidgetFiles.activation("vervetwodo-preview://task?id=short"))
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun snapshotsReplaceAtomicallyAndRetainUnicode() {
        val directory = Files.createTempDirectory("widget-test")
        try {
            val snapshot = WidgetSnapshot(revision = 4, locale = "fr", tasks = listOf(WidgetTask("task-id", "Préparer demain")))
            WidgetFiles.publish(directory, WidgetSnapshot())
            WidgetFiles.publish(directory, snapshot)
            assertEquals(snapshot, protocolJson.decodeFromString<WidgetSnapshot>(Files.readString(directory.resolve("snapshot.json"))))
            assertEquals(1, Files.list(directory).use { it.count().toInt() })
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun inboxRejectsUnsupportedProtocolsAndDoesNotReadTemporaryFiles() {
        val directory = Files.createTempDirectory("widget-test")
        try {
            WidgetFiles.commands(directory)
            val command = TaskCommand(version = 5, id = "command-identity-001", action = "complete", taskId = "task-id", completed = true)
            val path = directory.resolve("commands/command.json")
            Files.writeString(path, protocolJson.encodeToString(command))
            Files.writeString(directory.resolve("commands/.pending.tmp"), "partial")
            assertEquals(listOf(path), WidgetFiles.commands(directory))
            assertThrows(IllegalArgumentException::class.java) { WidgetFiles.readCommand(path) }
        } finally { directory.toFile().deleteRecursively() }
    }
}
