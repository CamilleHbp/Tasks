package studio.camille.vervetwodo.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import studio.camille.vervetwodo.integration.*
import java.util.Locale
import java.util.ResourceBundle

class SubtaskEntryTest {
    @get:Rule val compose = createComposeRule()

    @Test fun enterCreatesOneMultilineStepAndKeepsFocus() {
        var task by mutableStateOf(WidgetTask(java.util.UUID.randomUUID().toString(), "Task"))
        var saved: WidgetTask? = null
        val strings = ResourceBundle.getBundle("messages", Locale.ENGLISH)
        compose.setContent {
            MaterialTheme {
                TaskEditor(task, null, emptyList(), { strings.getString(it) }, false, Modifier,
                    { task = it }, {}, { saved = it })
            }
        }
        val composer = compose.onNodeWithTag("subtask-composer")
        composer.performScrollTo().performClick().performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertTrue(task.checklist.isEmpty()) }
        composer.performTextInput("First line")
        composer.performKeyInput { keyDown(Key.ShiftLeft); pressKey(Key.Enter); keyUp(Key.ShiftLeft) }
        compose.runOnIdle { assertTrue(task.checklist.isEmpty()) }
        composer.performTextInput("Second line")
        composer.performKeyInput { pressKey(Key.Enter) }
        composer.assertIsFocused()
        compose.runOnIdle { assertEquals(listOf("First line\nSecond line"), task.checklist.map { it.title }) }
        composer.performTextInput("Another step")
        composer.performKeyInput { pressKey(Key.NumPadEnter) }
        compose.runOnIdle { assertEquals(2, task.checklist.size) }
        composer.performTextInput("Unsubmitted\nmultiline draft")
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle {
            assertEquals(listOf("First line\nSecond line", "Another step", "Unsubmitted\nmultiline draft"), saved?.checklist?.map { it.title })
        }
    }
}
