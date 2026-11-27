package cn.super12138.todo

import cn.super12138.todo.ui.components.SyncAttention
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import studio.camille.vervetwodo.integration.*
import java.io.File
import java.util.*

class SyncAttentionTest {
    @get:Rule val compose = createComposeRule()
    private fun sample() = SyncReply(SyncState(authenticated = true, records = listOf(SyncRecord("task", "task", mapOf(
        "title" to listOf(SyncVersion("title", "Prepare the next release")),
        "details" to listOf(SyncVersion("a", "Review the desktop release on Monday.", source = SyncSource("mac", "Mac", "macos")),
            SyncVersion("b", "Review the desktop and Android releases on Tuesday.", source = SyncSource("phone", "Fairphone 5", "android"))))))))
    private fun texts(locale: Locale): (String) -> String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val localized = context.createConfigurationContext(android.content.res.Configuration(context.resources.configuration).apply { setLocale(locale) })
        return { key ->
            val name = key.replace(Regex("(?<!^)(?=[A-Z])"), "_").lowercase()
            val id = localized.resources.getIdentifier(name, "string", context.packageName)
            localized.getString(id)
        }
    }
    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.getExternalFilesDir(null), "sync-$name.png")
        file.outputStream().use { compose.onNode(isDialog()).captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun requiresExplicitChoiceAndResetsWhenRemoteVersionsChange() {
        var status by mutableStateOf(sample())
        var choice: String? = null
        val strings = texts(Locale.ENGLISH)
        compose.setContent { MaterialTheme { SyncAttention(status, false, { strings(it) }, {}, {}, { _, _, value -> choice = value }) } }
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
        val strings = texts(Locale.FRENCH)
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.3f)) { MaterialTheme(colorScheme = darkColorScheme()) {
            SyncAttention(sample(), true, { strings(it) }, {}, {}, { _, _, _ -> fail("Must not resolve while busy") })
        } } }
        compose.onNodeWithText("Vos appareils ont des versions différentes").assertExists()
        compose.onNodeWithText("Sélectionnez une version pour continuer").assertIsNotEnabled()
        capture("french-dark")
    }
    @Test fun errorsStayVisibleDuringRetryAndOfferSettings() {
        var busy by mutableStateOf(false)
        var opened = false
        val strings = texts(Locale.ENGLISH)
        compose.setContent { MaterialTheme { SyncAttention(SyncReply(error = "offline"), busy,
            { strings(it) }, { busy = true }, { opened = true }, { _, _, _ -> }) } }
        compose.onNodeWithText("Retry sync").performClick()
        compose.onNodeWithText("Your devices may be out of sync").assertExists()
        compose.onNodeWithText("Retry sync").assertIsNotEnabled()
        compose.onNodeWithText("Sync settings").performClick()
        compose.runOnIdle { assertTrue(opened) }
    }
}
