package studio.camille.vervetwodo.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import studio.camille.vervetwodo.integration.*
import java.util.Locale

class TaskMarkdownTest {
    @get:Rule val compose = createComposeRule()

    @Test fun taskDetailsRenderMarkdownInsteadOfSource() {
        val task = WidgetTask("markdown-test", "Markdown task", details = "# Heading\n\n**Bold** and *italic*\n\n- List item\n\n[Example](https://example.com)\n\n`code`")
        compose.setContent {
            MaterialTheme {
                TaskWorkspace(WidgetSnapshot(tasks = listOf(task)), { it }, Locale.ENGLISH, false,
                    TaskCommand(id = "open", action = "open", taskId = task.id), 0, 0,
                    { _, _ -> }, {}, {})
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Heading").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Heading").assertExists()
        compose.onNodeWithText("Bold and italic").assertExists()
        compose.onNodeWithText("List item").assertExists()
        compose.onNodeWithText("Example").assertExists()
        compose.onNodeWithText("code", substring = true).assertExists()
        compose.onNodeWithText(task.details).assertDoesNotExist()
    }
}
