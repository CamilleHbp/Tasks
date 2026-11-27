package studio.camille.vervetwodo.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import studio.camille.vervetwodo.integration.*
import java.util.Locale

class WidgetNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun widgetClickSurvivesAnOpenEditorAndCanBeEdited() {
        val first = WidgetTask("first-task-identity", "First task", details = "First detail")
        val second = WidgetTask("second-task-identity", "Second task", details = "Requested detail")
        val activation = mutableStateOf(TaskCommand(id = "first-open", action = "open", taskId = first.id))
        compose.setContent {
            MaterialTheme {
                TaskWorkspace(WidgetSnapshot(tasks = listOf(first, second)), { it }, Locale.ENGLISH, false,
                    activation.value, 0, 0, { _, _ -> }, {}, {})
            }
        }
        compose.onNodeWithText("First detail").assertExists()
        compose.onNodeWithText("edit").performClick()
        compose.runOnIdle { activation.value = TaskCommand(id = "second-open", action = "open", taskId = second.id) }
        compose.onNodeWithText("save").assertExists()
        compose.onNodeWithText("Requested detail").assertDoesNotExist()
        compose.onNodeWithText("cancel").performClick()
        compose.onNodeWithText("discard").performClick()
        compose.onNodeWithText("Requested detail").assertExists()
        compose.onNodeWithText("edit").performClick()
        compose.onNodeWithText("save").assertExists()
        compose.onNodeWithText("Requested detail").assertExists()
    }
}
