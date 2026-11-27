package studio.camille.vervetwodo.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*

object Platform {
    val mac = System.getProperty("os.name").startsWith("Mac")
    val windows = System.getProperty("os.name").startsWith("Windows")
    private val helpers: Path = (listOfNotNull(System.getProperty("compose.application.resources.dir")).map(Path::of) +
        listOf(Path.of("desktopApp/build/generated/resources/common"), Path.of("build/generated/resources/common")))
        .firstOrNull { Files.isDirectory(it) } ?: error("Application resources are missing")
    val helper: Path get() = helpers.resolve(if (windows) "vervetwodo-helper.exe" else "vervetwodo-helper")

    val directory: Path by lazy {
        System.getenv("VERVETWODO_PREVIEW_DATA")?.let { return@lazy Path.of(it) }
        if (mac) {
            val language = java.util.prefs.Preferences.userRoot()
                .node("studio/camille/vervetwodo/preview").get("language", "system")
            val storage = native("storage", language).trim()
            check(storage.isNotEmpty()) { "storage_unavailable" }
            return@lazy Path.of(storage)
        }
        val root = if (windows) Path.of(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"))
            else if (mac) Path.of(System.getProperty("user.home"), "Library", "Application Support")
            else Path.of(System.getenv("XDG_DATA_HOME") ?: "${System.getProperty("user.home")}/.local/share")
        root.resolve("VerveTwoDo/Preview")
    }

    fun initializeNotifications() {
        if (mac) MacNotifications.initialize(helpers.resolve("libVerveNotifications.dylib"))
    }

    fun notify(title: String, body: String) {
        if (mac) {
            initializeNotifications()
            MacNotifications.schedule(title, body)
            return
        }
        val payload = buildJsonObject { put("title", title); put("body", body) }.toString()
        native("notify", payload)
    }

    private fun native(command: String, payload: String = ""): String {
        val bundledBridge = helpers.parent?.parent?.resolve("MacOS/VerveNative")
        val executable = if (mac && bundledBridge != null && Files.isExecutable(bundledBridge)) bundledBridge
            else helpers.resolve(if (mac) "VerveNative" else "VerveWidgets/VerveWidgets.exe")
        val process = ProcessBuilder(executable.toString(), command).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        process.outputStream.bufferedWriter().use { it.write(payload) }
        // The system folder picker for legacy-data migration may stay open until
        // the user chooses a folder or cancels startup.
        if (command == "storage") process.waitFor()
        else if (!process.waitFor(20, TimeUnit.SECONDS)) { process.destroyForcibly(); error("native_timeout") }
        check(process.exitValue() == 0) { "native_failed" }
        return process.inputStream.bufferedReader().readText()
    }
}
