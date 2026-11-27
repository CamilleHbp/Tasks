package studio.camille.vervetwodo.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import studio.camille.vervetwodo.integration.*
import java.util.Locale
import java.util.ResourceBundle

class RepeatEditorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun inlineIntervalAndUnitSaveTogetherAndRejectEmptyInterval() {
        var task by mutableStateOf(WidgetTask("repeat-editor-test", "Task", dueDate = "2026-10-16"))
        var saved: WidgetTask? = null
        val strings = ResourceBundle.getBundle("messages", Locale.ENGLISH)
        compose.setContent {
            MaterialTheme {
                TaskEditor(task, null, emptyList(), { strings.getString(it) }, false, Modifier,
                    { task = it }, {}, { saved = it })
            }
        }
        compose.onNodeWithText("Repeat").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Interval (1–999)").assertTextEquals("1")
        compose.onNodeWithContentDescription("Interval (1–999)").performTextReplacement("2")
        compose.onNodeWithContentDescription("Repeat unit").performClick()
        compose.onNodeWithText("years").performClick()
        compose.runOnIdle {
            assertEquals(2, task.recurrence?.interval)
            assertEquals("yearly", task.recurrence?.frequency)
        }
        compose.onNodeWithContentDescription("Interval (1–999)").performTextClearance()
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle { assertNull(saved) }
        compose.onNodeWithText("Enter a number from 1 to 999.").assertExists()
        compose.onNodeWithContentDescription("Interval (1–999)").performTextReplacement("3")
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle {
            assertEquals(Recurrence("yearly", 3, "2026-10-16"), saved?.recurrence)
            assertNull(saved?.rollover)
        }
        compose.onNodeWithContentDescription("Repeat unit").performClick()
        compose.onNodeWithText("Does not repeat").performClick()
        compose.runOnIdle { assertNull(task.recurrence) }
        compose.onNodeWithText("Repeat").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Interval (1–999)").assertTextEquals("1")
    }
}
