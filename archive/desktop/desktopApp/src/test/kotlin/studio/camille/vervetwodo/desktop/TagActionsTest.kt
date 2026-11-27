package studio.camille.vervetwodo.desktop

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import studio.camille.vervetwodo.integration.*
import java.io.File
import java.util.*
import javax.imageio.ImageIO

class TagActionsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun editCancelAndDeleteKeepTasksInEnglish() = checkFlow(Locale.ENGLISH, false)
    @Test fun editCancelAndDeleteKeepTasksInFrench() = checkFlow(Locale.FRENCH, true)

    private fun checkFlow(locale: Locale, dark: Boolean) {
        val strings = ResourceBundle.getBundle("messages", locale)
        val icons = ResourceBundle.getBundle("camille_icons", locale)
        val text: Texts = { if (icons.containsKey(it)) icons.getString(it) else strings.getString(it) }
        val tag = TaskTag("work-tag-identity", "Work")
        val other = TaskTag("home-tag-identity", "Home")
        var live by mutableStateOf(WidgetSnapshot(tags = listOf(tag, other, other.copy(id = "duplicate-home-id")), tasks = listOf(
            WidgetTask("task-identity-01", "Keep this task", tags = listOf(tag.id, other.id)),
            WidgetTask("task-identity-02", "Completed task", completed = true, tags = listOf(tag.id)))))
        var commits = 0
        compose.setContent { MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
            Surface(Modifier.requiredSize(1000.dp, 740.dp)) {
                TaskWorkspace(live, text, locale, false, null, 0, 0, { build, done ->
                    val command = build(live)
                    live = live.copy(tags = command.tags ?: live.tags,
                        tasks = live.tasks.map { task -> command.tasks.find { it.id == task.id } ?: task })
                    commits++
                    done()
                }, {}, {})
            }
        } }
        compose.onNodeWithTag("sidebar-tags-action").performClick()
        val edit = text("editTag") + ": Work"
        val delete = text("deleteTag") + ": Work"
        capture("tags-${locale.language}")
        compose.onNodeWithContentDescription(edit).performClick()
        compose.onNodeWithText(text("deleteTag")).assertDoesNotExist()
        compose.onNodeWithText(text("tagName")).performTextReplacement("Renamed")
        compose.onNodeWithText(text("cancel")).performClick()
        compose.runOnIdle { assertEquals("Work", live.tags.first { it.id == tag.id }.name) }
        compose.onNodeWithContentDescription(text("deleteTag") + ": Work").performClick()
        compose.onNodeWithText(text("deleteTagBody")).assertExists()
        compose.onNodeWithText(text("cancel")).performClick()
        compose.runOnIdle { assertEquals(0, commits); assertEquals(3, live.tags.size) }
        compose.onNodeWithContentDescription(text("deleteTag") + ": Work").performClick()
        capture("delete-${locale.language}")
        compose.onNodeWithText(text("delete"), substring = false).performClick()
        compose.runOnIdle {
            assertEquals(1, commits)
            assertEquals(listOf(other, other.copy(id = "duplicate-home-id")), live.tags)
            assertEquals(2, live.tasks.size)
            assertTrue(live.tasks.none { it.deleted || tag.id in it.tags })
            assertEquals(listOf(other.id), live.tasks.first().tags)
            assertTrue(live.tasks.last().completed)
        }
        compose.onNodeWithContentDescription(delete).assertDoesNotExist()
        compose.onNodeWithContentDescription(text("deleteTag") + ": Work").assertDoesNotExist()
    }

    private fun capture(name: String) {
        val file = File("../build/tag-actions-review/$name.png").apply { parentFile.mkdirs() }
        ImageIO.write(compose.onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", file)
    }
}
