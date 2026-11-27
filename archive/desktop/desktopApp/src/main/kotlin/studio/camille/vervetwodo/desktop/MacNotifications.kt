package studio.camille.vervetwodo.desktop

import java.nio.file.Path
import java.io.IOException
import java.util.UUID
import kotlinx.serialization.encodeToString
import studio.camille.vervetwodo.integration.ScheduledReminder
import studio.camille.vervetwodo.integration.protocolJson

/** Cocoa must receive foreground callbacks in the resident app, not a scheduling subprocess. */
internal object MacNotifications {
    private var loaded = false

    @Synchronized
    fun initialize(library: Path) {
        try {
            if (!loaded) {
                System.load(library.toAbsolutePath().toString())
                loaded = true
            }
            installNative()
        } catch (error: LinkageError) {
            throw IOException("The native notification library could not be loaded", error)
        }
    }

    fun schedule(title: String, body: String) {
        scheduleNative("preview-test-${UUID.randomUUID()}", title, body)
    }

    fun reconcile(reminders: List<ScheduledReminder>) = reconcileNative(protocolJson.encodeToString(reminders))

    private external fun installNative()
    private external fun scheduleNative(identifier: String, title: String, body: String)
    private external fun reconcileNative(payload: String)
}
