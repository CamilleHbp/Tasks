package studio.camille.vervetwodo.integration

import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.net.URI
import java.net.URLDecoder
import java.util.UUID

object WidgetFiles {
    fun activation(uri: String): TaskCommand? = runCatching {
        if (uri.length > 4096) return null
        val parsed = URI(uri)
        if (parsed.scheme != "vervetwodo-preview") return null
        when (parsed.host) {
            "open" -> TaskCommand(id = UUID.randomUUID().toString(), action = "open")
            "add" -> TaskCommand(id = UUID.randomUUID().toString(), action = "add")
            "task" -> {
                val id = parsed.rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("id=") }
                    ?.substring(3)?.let { URLDecoder.decode(it, Charsets.UTF_8) } ?: return null
                if (id.length !in 16..128) return null
                TaskCommand(id = UUID.randomUUID().toString(), action = "open", taskId = id)
            }
            else -> null
        }
    }.getOrNull()

    fun enqueue(directory: Path, command: TaskCommand) {
        require(command.id.matches(Regex("[a-zA-Z0-9-]{16,128}")))
        val inbox = directory.resolve("commands")
        Files.createDirectories(inbox)
        val temporary = Files.createTempFile(inbox, ".command-", ".tmp")
        try {
            Files.writeString(temporary, protocolJson.encodeToString(command))
            Files.move(temporary, inbox.resolve("${command.id}.json"), StandardCopyOption.ATOMIC_MOVE)
        } finally { Files.deleteIfExists(temporary) }
    }

    fun publish(directory: Path, snapshot: WidgetSnapshot) {
        Files.createDirectories(directory)
        val temporary = Files.createTempFile(directory, ".snapshot-", ".tmp")
        try {
            Files.writeString(temporary, protocolJson.encodeToString(snapshot.copy(tasks = snapshot.tasks.filterNot { it.deleted })))
            try {
                Files.move(temporary, directory.resolve("snapshot.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, directory.resolve("snapshot.json"), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { Files.deleteIfExists(temporary) }
    }

    fun commands(directory: Path): List<Path> {
        val inbox = directory.resolve("commands")
        Files.createDirectories(inbox)
        return Files.list(inbox).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".json") && !Files.isSymbolicLink(it) }
                .sorted().limit(100).toList()
        }
    }

    fun readCommand(path: Path): TaskCommand {
        require(Files.size(path) <= 64 * 1024) { "invalid_command" }
        val command = protocolJson.decodeFromString<TaskCommand>(Files.readString(path))
        require(command.version == PROTOCOL_VERSION && command.id.length in 16..128) { "invalid_command" }
        require(command.action in setOf("complete", "open", "add")) { "unknown_action" }
        require(command.action != "complete" || command.taskId.length in 16..128) { "invalid_task" }
        return command
    }
}
