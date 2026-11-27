package studio.camille.vervetwodo.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
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

class TaskModalFlowTest {
    @get:Rule val compose = createComposeRule()
    private fun texts(language: String): Texts {
        val bundle = ResourceBundle.getBundle("messages", Locale.forLanguageTag(language))
        return { bundle.getString(it) }
    }
    private fun capture(name: String) {
        val file = File("../.impeccable/review/task-modal/$name.png").apply { parentFile.mkdirs() }
        ImageIO.write(compose.onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", file)
    }
    private val sample = WidgetTask("task-identity-0001", "Prepare the next release",
        details = "Review the **desktop experience** and bring the final details together.", dueDate = "2026-10-30",
        tags = listOf("work"), priority = 2, recurrence = Recurrence("weekly", 2, "2026-10-30"),
        checklist = listOf(ChecklistItem("step-identity", "Review task creation"), ChecklistItem("completed-step", "Review French labels", true)))
    private val snapshot = WidgetSnapshot(tasks = listOf(sample), tags = listOf(TaskTag("work", "Work")))

    @Test fun viewingIsReadOnlyAndDiscardReturnsToOriginalDetail() {
        val text = texts("en")
        compose.setContent { MaterialTheme { TaskWorkspace(snapshot, text, Locale.ENGLISH, false,
            TaskCommand(id = "open-task", action = "open", taskId = sample.id), 0, 0, { _, _ -> }, {}, {}) } }
        compose.onNodeWithText(text("viewTask")).assertExists()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetText)).assertCountEquals(1) // background search only
        compose.onNodeWithContentDescription("Review task creation").assertHasNoClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text("openTasks")))
        compose.onNodeWithContentDescription("Review French labels").assertHasNoClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text("completed")))
        capture("view-english-light")
        compose.onNodeWithText(text("edit")).performClick()
        compose.onNodeWithText(text("editTask")).assertExists()
        compose.onNodeWithText(text("title")).performTextReplacement("Changed title")
        capture("edit-english-light")
        compose.onNodeWithText(text("cancel")).performClick()
        compose.onNodeWithText(text("keepEditing")).performClick()
        compose.onNodeWithText("Changed title").assertExists()
        compose.onNodeWithText(text("cancel")).performClick()
        compose.onNodeWithText(text("discard")).performClick()
        compose.onNodeWithText(text("viewTask")).assertExists()
        compose.onNodeWithText("Changed title").assertDoesNotExist()
        compose.onNodeWithText(text("close")).performClick()
        compose.onNodeWithText(text("viewTask")).assertDoesNotExist()
    }

    @Test fun savingExistingTaskReturnsToReadOnlyView() {
        var live by mutableStateOf(snapshot)
        val text = texts("en")
        compose.setContent { MaterialTheme { TaskWorkspace(live, text, Locale.ENGLISH, false,
            TaskCommand(id = "save-open", action = "open", taskId = sample.id), 0, 0,
            { build, done -> val command = build(live); live = live.copy(tasks = command.tasks); done() }, {}, {}) } }
        compose.onNodeWithText(text("edit")).performClick()
        compose.onNodeWithText(text("title")).performTextReplacement("Saved title")
        compose.onNodeWithText(text("save")).performClick()
        compose.onNodeWithText(text("viewTask")).assertExists()
        compose.onNodeWithText(text("save")).assertDoesNotExist()
        compose.runOnIdle { assertEquals("Saved title", live.tasks.single().title) }
    }

    @Test fun saveFailureMessageIsVisibleAboveModalScrim() {
        val text = texts("en")
        compose.setContent { MaterialTheme { TaskWorkspace(snapshot, text, Locale.ENGLISH, false,
            null, 1, 0, { _, _ -> }, {}, {}, operationMessage = "taskChanged") } }
        compose.onNodeWithText(text("taskChanged")).assertIsDisplayed()
        compose.onNodeWithText(text("title")).assertExists()
    }

    @Test fun frenchDarkViewAtNarrowWidthKeepsActionsAndMetadataAccessible() {
        val text = texts("fr")
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) {
            Surface(Modifier.requiredSize(660.dp, 480.dp)) { TaskDetail(sample, snapshot, text, Locale.FRENCH, false, {}, {}, {}) }
        } }
        compose.onNodeWithText(text("edit")).assertIsDisplayed()
        capture("view-narrow-french-dark")
        compose.onNodeWithText(text("priority")).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text("edit")).assertIsDisplayed()
        capture("metadata-narrow-french-dark")
    }

    @Test fun frenchWideReadOnlyView() {
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) {
            Surface(Modifier.requiredSize(1000.dp, 760.dp)) { TaskDetail(sample, snapshot, texts("fr"), Locale.FRENCH, false, {}, {}, {}) }
        } }
        compose.onNodeWithText(texts("fr")("dueDate")).assertIsDisplayed()
        capture("view-wide-french-dark")
    }
}
