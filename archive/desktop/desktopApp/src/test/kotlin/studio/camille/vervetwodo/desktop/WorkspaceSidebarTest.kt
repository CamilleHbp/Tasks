package studio.camille.vervetwodo.desktop

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.jetbrains.skia.Image
import studio.camille.vervetwodo.integration.*
import java.io.File
import java.time.LocalDate
import java.util.Locale
import java.util.ResourceBundle
import java.util.prefs.Preferences

class WorkspaceSidebarTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sectionsToggleIndependentlyAndLongTagsRemainReachable() {
        var agenda by mutableStateOf(true)
        var tags by mutableStateOf(true)
        var picked = ""
        var french by mutableStateOf(false)
        var width by mutableStateOf(172)
        val snapshot = WidgetSnapshot(tags = listOf(TaskTag("work", "A very long project tag that wraps")) +
            (1..20).map { TaskTag("tag-$it", "Project $it") })
        compose.setContent {
            val locale = if (french) Locale.FRENCH else Locale.ENGLISH
            val strings = ResourceBundle.getBundle("messages", locale)
            MaterialTheme(colorScheme = if (french) darkColorScheme() else lightColorScheme()) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                WorkspaceSidebar(snapshot, { strings.getString(it) }, locale, LocalDate.now(), "tags", "work", null,
                    agenda, tags, Modifier.requiredSize(width.dp, 700.dp),
                    { agenda = !agenda }, { tags = !tags }, { picked = it }, { picked = it },
                    { picked = "agenda" }, { picked = "manage" })
                }
            }
        }
        compose.onNodeWithTag("sidebar-date-tomorrow").performClick()
        compose.runOnIdle { assertEquals("tomorrow", picked) }
        compose.onNodeWithTag("sidebar-agenda").performClick()
        compose.onNodeWithTag("sidebar-date-tomorrow").assertDoesNotExist()
        compose.onNodeWithTag("sidebar-tag-work").assertExists().performClick()
        compose.runOnIdle { assertEquals("work", picked) }
        compose.onNodeWithTag("sidebar-tag-tag-20").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("tag-20", picked) }
        compose.onNodeWithTag("sidebar-tags").performScrollTo().performClick()
        compose.onNodeWithTag("sidebar-tag-work").assertDoesNotExist()
        compose.onNodeWithTag("sidebar-tags-action").performClick()
        compose.runOnIdle { assertEquals("manage", picked); assertEquals(false, tags) }
        compose.onNodeWithTag("sidebar-tags").performClick()
        compose.onNodeWithTag("sidebar-agenda").performScrollTo().performClick()
        capture("sidebar-en-light")
        compose.runOnIdle { french = true }
        compose.onNodeWithText("Demain").assertExists()
        capture("sidebar-fr-dark")
        compose.runOnIdle { width = 136 }
        compose.onNodeWithTag("sidebar-tags-action").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("sidebar-agenda").performScrollTo()
        capture("sidebar-fr-narrow")
    }

    @Test fun dateAndTagShortcutsScopeTasksAndSearch() {
        val prefs = Preferences.userRoot().node("studio/camille/vervetwodo/preview/views")
        val keys = listOf("sidebarAgendaExpanded", "sidebarTagsExpanded", "status")
        val before = keys.associateWith { prefs.get(it, null) }
        try {
            prefs.putBoolean("sidebarAgendaExpanded", true); prefs.putBoolean("sidebarTagsExpanded", true)
            prefs.put("status", "all")
            val today = LocalDate.now()
            val snapshot = WidgetSnapshot(tags = listOf(TaskTag("work", "Work")), tasks = listOf(
                WidgetTask("a", "Alpha today", dueDate = today.toString()),
                WidgetTask("b", "Beta tomorrow", dueDate = today.plusDays(1).toString(), tags = listOf("work")),
                WidgetTask("c", "Gamma undated", tags = listOf("work"))))
            val strings = ResourceBundle.getBundle("messages", Locale.ENGLISH)
            compose.setContent { MaterialTheme {
                TaskWorkspace(snapshot, { strings.getString(it) }, Locale.ENGLISH, false, null, 0, 0, { _, _ -> }, {}, {})
            } }
            compose.onNodeWithTag("sidebar-date-tomorrow").performClick()
            compose.onNodeWithText("Beta tomorrow").assertExists()
            compose.onNodeWithText("Alpha today").assertDoesNotExist()
            compose.onNodeWithText("Search tasks").performTextInput("Alpha")
            compose.onNodeWithText("Alpha today").assertDoesNotExist()
            compose.onNodeWithText("Beta tomorrow").assertDoesNotExist()
            compose.onNodeWithTag("sidebar-tag-work").performScrollTo().performClick()
            compose.onNodeWithText("Beta tomorrow").assertExists()
            compose.onNodeWithText("Gamma undated").assertExists()
            compose.onNodeWithText("Alpha today").assertDoesNotExist()
            compose.onNodeWithTag("sidebar-tags").performScrollTo().performClick()
            compose.onNodeWithText("Beta tomorrow").assertExists()
            compose.onNodeWithText("Gamma undated").assertExists()
            compose.onNodeWithTag("sidebar-tag-work").assertDoesNotExist()
            compose.onNodeWithTag("sidebar-tags-action").performClick()
            compose.onNodeWithText("New tag").assertExists()
            compose.onNodeWithTag("sidebar-tag-work").assertDoesNotExist()
            compose.onNodeWithTag("sidebar-tags").performClick()
            compose.onNodeWithTag("sidebar-tag-work").assertExists()
            compose.onNodeWithText("New tag").assertExists()
            compose.onNodeWithTag("sidebar-date-tomorrow").performScrollTo().performClick()
            compose.onNodeWithTag("sidebar-agenda").performScrollTo().performClick()
            compose.onNodeWithText("Beta tomorrow").assertExists()
            compose.onNodeWithText("Alpha today").assertDoesNotExist()
            compose.onNodeWithTag("sidebar-agenda-action").performClick()
            compose.onNodeWithText("Alpha today").assertExists()
            compose.onNodeWithTag("sidebar-date-tomorrow").assertDoesNotExist()
        } finally {
            before.forEach { (key, value) -> if (value == null) prefs.remove(key) else prefs.put(key, value) }
        }
    }

    private fun capture(name: String) {
        val bitmap = compose.onRoot().captureToImage().asSkiaBitmap()
        val output = File("../build/sidebar-verification/$name.png")
        output.parentFile.mkdirs()
        output.writeBytes(Image.makeFromBitmap(bitmap).encodeToData()!!.bytes)
    }
}
