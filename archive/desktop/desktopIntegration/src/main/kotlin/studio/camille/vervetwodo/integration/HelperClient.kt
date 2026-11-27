package studio.camille.vervetwodo.integration

import kotlinx.serialization.encodeToString
import java.io.Closeable
import java.io.IOException
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class HelperFailure(val code: String) : IOException(code)

/** Serialized durable requests, with independent unsolicited sync invalidations. */
class HelperClient(private val executable: Path, private val dataDirectory: Path) : Closeable {
    private var process: Process? = null
    private var reader: java.io.BufferedReader? = null
    private var responses: LinkedBlockingQueue<Result<HelperResponse>>? = null
    private var closed = false
    @Volatile var onSyncChange: (() -> Unit)? = null

    @Synchronized
    fun request(method: String, command: TaskCommand? = null, locale: String? = null): WidgetSnapshot {
        return exchange(HelperRequest(id = UUID.randomUUID().toString(), method = method, command = command, locale = locale)).snapshot
            ?: throw HelperFailure("invalid_response")
    }

    @Synchronized
    fun exchange(request: HelperRequest): HelperResponse {
        check(!closed)
        var failure: Exception? = null
        repeat(2) {
            try {
                val child = process?.takeIf { it.isAlive } ?: start()
                child.outputWriter(Charsets.UTF_8).apply {
                    write(protocolJson.encodeToString(request)); newLine(); flush()
                }
                val response = responses!!.poll(70, TimeUnit.SECONDS)?.getOrThrow()
                    ?: throw IOException("helper_timeout")
                if (response.version != PROTOCOL_VERSION || response.id != request.id) throw HelperFailure("invalid_response")
                response.error?.let { throw HelperFailure(it) }
                return response
            } catch (error: HelperFailure) {
                throw error
            } catch (error: Exception) {
                failure = error
                stop()
            }
        }
        throw IOException("helper_unavailable", failure)
    }

    private fun start(): Process {
        val child = ProcessBuilder(executable.toAbsolutePath().toString(), "-data-dir", dataDirectory.toAbsolutePath().toString())
            .redirectError(ProcessBuilder.Redirect.appendTo(dataDirectory.resolve("helper.log").toFile())).start()
        val input = child.inputStream.bufferedReader(Charsets.UTF_8)
        val queue = LinkedBlockingQueue<Result<HelperResponse>>()
        reader = input
        responses = queue
        process = child
        Thread({
            try {
                while (true) {
                    val response = protocolJson.decodeFromString<HelperResponse>(readBoundedLine(input))
                    if (response.event == "syncChanged") runCatching { onSyncChange?.invoke() }
                    else queue.put(Result.success(response))
                }
            } catch (error: Exception) { queue.offer(Result.failure(error)) }
        }, "sync-helper-reader").apply { isDaemon = true; start() }
        return child
    }

    private fun readBoundedLine(input: java.io.BufferedReader): String {
        val result = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) throw IOException("helper_closed")
            if (c == '\n'.code) return result.toString()
            if (result.length >= 4 * 1024 * 1024) throw IOException("response_too_large")
            result.append(c.toChar())
        }
    }

    private fun stop() {
        process?.destroyForcibly()
        process?.waitFor(2, TimeUnit.SECONDS)
        reader?.close()
        process = null
        reader = null
        responses = null
    }

    @Synchronized
    override fun close() { closed = true; onSyncChange = null; stop() }
}
