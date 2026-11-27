package studio.camille.vervetwodo.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
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

class NewTaskDialogTest {
    @get:Rule val compose = createComposeRule()
    private fun texts(language: String): Texts {
        val bundle = ResourceBundle.getBundle("messages", Locale.forLanguageTag(language))
        return { bundle.getString(it) }
    }
    private fun capture(name: String) {
        val file = File("../.impeccable/review/desktop-composer/$name.png").apply { parentFile.mkdirs() }
        ImageIO.write(compose.onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", file)
    }
    private fun sample() = WidgetTask(UUID.randomUUID().toString(), "Prepare the next release",
        details = "Review the desktop experience and bring the final details together.", dueDate = "2026-10-30",
        checklist = listOf(ChecklistItem(UUID.randomUUID().toString(), "Review task creation")))

    @Test fun modalSavesPendingChecklistWithKeyboardAndProtectsBusyDismissal() {
        var task by mutableStateOf(sample())
        var busy by mutableStateOf(false)
        var saved: WidgetTask? = null
        var cancelled = false
        compose.setContent { MaterialTheme { NewTaskDialog(task, emptyList(), texts("en"), busy,
            { task = it }, { cancelled = true }, { saved = it }) } }
        compose.onNodeWithText("Save").assertIsDisplayed()
        capture("modal-english-light")
        val step = compose.onNodeWithTag("subtask-composer")
        step.performScrollTo().performTextInput("Check translations")
        step.performKeyInput { keyDown(Key.MetaLeft); pressKey(Key.Enter); keyUp(Key.MetaLeft) }
        compose.runOnIdle { assertEquals("Check translations", saved?.checklist?.last()?.title); saved = null; busy = true }
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Close").assertIsNotEnabled()
        step.performKeyInput { pressKey(Key.Escape) }
        compose.runOnIdle { assertFalse(cancelled); assertNull(saved); busy = false }
        compose.onNodeWithText("Close").performClick()
        compose.runOnIdle { assertTrue(cancelled) }
    }

    @Test fun workspaceCancelKeepsDraftUntilDiscardConfirmed() {
        compose.setContent { MaterialTheme { TaskWorkspace(WidgetSnapshot(), texts("en"), Locale.ENGLISH, false,
            null, 1, 0, { _, _ -> }, {}, {}) } }
        compose.onNodeWithText("Title").performTextInput("Keep this draft")
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText(texts("en")("keepEditing")).performClick()
        compose.onNodeWithText("Keep this draft").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText(texts("en")("discard")).performClick()
        compose.onNodeWithText("Keep this draft").assertDoesNotExist()
        compose.onNodeWithText("New task").assertExists()
    }

    @Test fun narrowFrenchEditorKeepsSaveVisibleAndSchedulingReachable() {
        var task by mutableStateOf(sample())
        val text = texts("fr")
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) {
            Surface(Modifier.requiredSize(660.dp, 480.dp)) { TaskEditor(task, null, emptyList(), text, false,
                Modifier, { task = it }, {}, {}, spacious = true) }
        } }
        compose.onNodeWithText(text("save")).assertIsDisplayed()
        capture("narrow-french-dark-writing")
        compose.onNodeWithText(text("priority")).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text("save")).assertIsDisplayed()
        capture("narrow-french-dark-scheduling")
    }

    @Test fun wideFrenchEditorShowsWritingBesideSchedule() {
        var task by mutableStateOf(sample().copy(recurrence = Recurrence("weekly", 2, "2026-10-30")))
        val text = texts("fr")
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) {
            Surface(Modifier.requiredSize(1000.dp, 760.dp)) { TaskEditor(task, null, emptyList(), text, false,
                Modifier, { task = it }, {}, {}, spacious = true) }
        } }
        compose.onNodeWithText(text("title")).assertIsDisplayed()
        compose.onNodeWithText(text("dueDate")).assertIsDisplayed()
        capture("wide-french-dark")
    }
}
