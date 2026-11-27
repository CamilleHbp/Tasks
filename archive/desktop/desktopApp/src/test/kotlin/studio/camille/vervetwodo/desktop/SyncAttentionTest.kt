package studio.camille.vervetwodo.desktop

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import studio.camille.vervetwodo.integration.*
import java.io.File
import java.util.*
import javax.imageio.ImageIO

class SyncAttentionTest {
    @get:Rule val compose = createComposeRule()
    private fun sample() = SyncReply(SyncState(authenticated = true, records = listOf(SyncRecord("task", "task", mapOf(
        "title" to listOf(SyncVersion("title", "Prepare the next release")),
        "details" to listOf(SyncVersion("a", "Review the desktop release on Monday.", source = SyncSource("mac", "Mac", "macos")),
            SyncVersion("b", "Review the desktop and Android releases on Tuesday.", source = SyncSource("phone", "Fairphone 5", "android"))))))))
    private fun capture(name: String) {
        val file = File("../.impeccable/review/sync-conflicts/$name.png").apply { parentFile.mkdirs() }
        ImageIO.write(compose.onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", file)
    }
    @Test fun requiresExplicitChoiceAndResetsWhenRemoteVersionsChange() {
        var status by mutableStateOf(sample())
        var choice: String? = null
        val strings = ResourceBundle.getBundle("messages", Locale.ENGLISH)
        compose.setContent { MaterialTheme { SyncAttention(status, false, { strings.getString(it) }, {}, {}, { _, _, value -> choice = value }) } }
        compose.onNodeWithText("Select a version to continue").assertIsNotEnabled()
        compose.onNodeWithText("Review the desktop release on Monday.").performClick()
        compose.runOnIdle { assertNull(choice) }
        capture("english-light")
        compose.onNodeWithText("Keep this version").performClick()
        compose.runOnIdle { assertEquals("Review the desktop release on Monday.", choice) }
        // A failed operation must leave the resolver present and the choice available to retry.
        compose.runOnIdle { status = status.copy(error = "sync_failed") }
        compose.onNodeWithText("Your devices have different versions").assertExists()
        compose.runOnIdle {
            val record = status.state.records.single()
            status = status.copy(state = status.state.copy(records = listOf(record.copy(fields = record.fields +
                ("details" to record.fields.getValue("details") + SyncVersion("c", "A newer edit"))))))
        }
        compose.onNodeWithText("Select a version to continue").assertIsNotEnabled()
        compose.onNodeWithText("A newer edit").assertExists()
        compose.runOnIdle { status = SyncReply() }
        compose.onNodeWithText("Your devices have different versions").assertDoesNotExist()
    }
    @Test fun frenchDarkAndBusyState() {
        val strings = ResourceBundle.getBundle("messages", Locale.FRENCH)
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) {
            SyncAttention(sample(), true, { strings.getString(it) }, {}, {}, { _, _, _ -> fail("Must not resolve while busy") })
        } }
        compose.onNodeWithText("Vos appareils ont des versions différentes").assertExists()
        compose.onNodeWithText("Sélectionnez une version pour continuer").assertIsNotEnabled()
        capture("french-dark")
    }
    @Test fun errorsStayVisibleDuringRetryAndOfferSettings() {
        var busy by mutableStateOf(false)
        var opened = false
        val strings = ResourceBundle.getBundle("messages", Locale.ENGLISH)
        compose.setContent { MaterialTheme { SyncAttention(SyncReply(error = "offline"), busy,
            { strings.getString(it) }, { busy = true }, { opened = true }, { _, _, _ -> }) } }
        compose.onNodeWithText("Retry sync").performClick()
        compose.onNodeWithText("Your devices may be out of sync").assertExists()
        compose.onNodeWithText("Retry sync").assertIsNotEnabled()
        compose.onNodeWithText("Sync settings").performClick()
        compose.runOnIdle { assertTrue(opened) }
    }
}
