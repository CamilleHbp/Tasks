package cn.super12138.todo

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.activity.compose.LocalActivity
import androidx.core.view.WindowCompat
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModelStore
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import androidx.room3.Room
import androidx.test.platform.app.InstrumentationRegistry
import cn.super12138.todo.logic.*
import cn.super12138.todo.logic.database.*
import cn.super12138.todo.logic.datastore.DataStoreManager
import cn.super12138.todo.ui.navigation.VerveDoScreen
import cn.super12138.todo.ui.pages.detail.*
import cn.super12138.todo.ui.pages.editor.*
import cn.super12138.todo.ui.pages.tasks.*
import cn.super12138.todo.ui.theme.VerveDoTheme
import cn.super12138.todo.utils.ConfettiController
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

class TaskDetailUiTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var database: TaskDatabase
    private lateinit var repository: TaskRepository
    private lateinit var settings: SettingsRepository
    private lateinit var tags: TagRepository
    private lateinit var detail: TaskDetailViewModel
    private val store = ViewModelStore()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var preferences: File
    private val sample = TaskEntity("Prepare the autumn garden", priority = 1f, id = 1,
        details = "Plan the new planting beds before the weekend. Keep space near the path for herbs and pollinator-friendly flowers.",
        tags = listOf("Home", "Garden"),
        dueDateMillis = LocalDate.now().atTime(14, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        dueTimeMinutes = 870,
        subtasks = listOf(Subtask("Measure the planting beds", true, "measure"),
            Subtask("Choose bulbs and native flowers", id = "choose"),
            Subtask("Pick up compost and mulch", id = "compost")))

    @Before fun prepare() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(context, TaskDatabase::class.java).build()
        repository = TaskRepository(database.taskDao())
        preferences = File(context.cacheDir, "detail-${System.nanoTime()}.preferences_pb")
        settings = SettingsRepository(DataStoreManager(PreferenceDataStoreFactory.create(scope = scope) { preferences }))
        tags = TagRepository(settings, repository)
        repository.insertTask(sample)
        repository.insertTask(TaskEntity("Book the train tickets", priority = 0f, id = 2))
        detail = TaskDetailViewModel(1, repository, tags, context.applicationContext as Application)
        store.put("detail", detail)
    }

    @After fun cleanUp() {
        store.clear()
        scope.cancel()
        database.close()
        preferences.delete()
    }

    @Test fun readTickEditAndReturnUsesLatestSavedTask() {
        var edit: TaskEntity? = null
        showDetail { edit = it }
        compose.onNodeWithText(sample.details).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.task_view_title)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.task_status_todo)).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.widget_complete_task, sample.content)).assertIsOff()
        compose.onNodeWithText(sample.content).performClick()
        assertFalse(saved().isCompleted)
        capture("detail-light")
        compose.onNodeWithText("Choose bulbs and native flowers").performScrollTo().performClick()
        compose.waitUntil(5_000) { saved().subtasks[1].isCompleted }
        assertFalse(saved().isCompleted)
        compose.onNodeWithContentDescription(context.getString(R.string.task_detail_edit)).performScrollTo().performClick()
        compose.runOnIdle { assertTrue(edit!!.subtasks[1].isCompleted) }
        runBlocking { repository.updateTask(saved().copy(content = "Updated garden plan")) }
        compose.waitUntil(5_000) { detail.uiState.value.task?.content == "Updated garden plan" }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Updated garden plan"))
        compose.onNodeWithText("Updated garden plan").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.widget_complete_task, "Updated garden plan")).performClick()
        compose.waitUntil(5_000) { saved().isCompleted }
        assertFalse(saved().subtasks.last().isCompleted)
        compose.onNodeWithContentDescription(context.getString(R.string.widget_restore_task, "Updated garden plan")).assertIsOn()
        compose.onNodeWithText(context.getString(R.string.task_status_completed)).assertIsDisplayed()
        capture("detail-completed")
        compose.onNodeWithContentDescription(context.getString(R.string.widget_restore_task, "Updated garden plan")).performClick()
        compose.waitUntil(5_000) { !saved().isCompleted }
    }

    @Test fun markdownDetailsRenderAndKeepSourceForEditing() {
        val source = "# Planting plan\n\nUse **native flowers** and *herbs*.\n\n- Buy seeds\n- Prepare soil\n\n`garden.txt`"
        runBlocking { repository.updateTask(sample.copy(details = source)) }
        var edited: TaskEntity? = null
        showDetail { edited = it }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Planting plan").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Planting plan").assertIsDisplayed()
        compose.onNodeWithText("Use native flowers and herbs.").assertExists()
        compose.onNodeWithText("Buy seeds").assertExists()
        compose.onNodeWithText(source).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.task_detail_edit)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(source, edited!!.details) }
        assertEquals(source, saved().details)
    }

    @Test fun markdownEditorShowsAndSavesRawSource() {
        val source = "# Plan\n\n**Keep this formatting**"
        val task = sample.copy(details = source)
        val model = EditorViewModel(task, repository, settings, tags, ConfettiController())
        store.put("editor", model)
        var didSave = false
        compose.setContent { VerveDoTheme(dynamicColor = false, animate = false) {
            TaskEditorPage(task = task, viewModel = model, onSaved = { didSave = true })
        } }
        val replacement = "# Updated plan\n\n- **Native flowers**\n- Herbs\n\n[Guide](https://example.com)"
        compose.onNode(hasText(source) and hasSetTextAction()).performScrollTo().performTextReplacement(replacement)
        compose.onNodeWithContentDescription(context.getString(R.string.action_save)).performClick()
        compose.waitUntil(5_000) { didSave }
        assertEquals(replacement, saved().details)
    }

    @Test fun editorAddsRenamesRemovesAndSavesChecklist() {
        val model = EditorViewModel(sample, repository, settings, tags, ConfettiController())
        store.put("editor", model)
        var saved = false
        var keyboard: SoftwareKeyboardController? = null
        compose.setContent { VerveDoTheme(dynamicColor = false, animate = false) {
            keyboard = LocalSoftwareKeyboardController.current
            SystemBars(false)
            TaskEditorPage(task = sample, viewModel = model, onSaved = { saved = true })
        } }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription(context.getString(R.string.task_subtask_label, 2)))
        compose.onNodeWithContentDescription(context.getString(R.string.task_subtask_label, 2))
            .performScrollTo().performTextReplacement("Choose spring bulbs")
        compose.onNodeWithContentDescription(context.getString(R.string.task_subtask_remove, 3))
            .performScrollTo().performClick()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("subtask-composer"))
        compose.onNodeWithTag("subtask-composer").performScrollTo().performTextInput("Water the new beds")
        compose.onNodeWithTag("subtask-composer").performImeAction()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription(context.getString(R.string.task_subtask_label, 1)))
        compose.runOnIdle { keyboard?.hide() }
        capture("editor-checklist")
        compose.onNodeWithContentDescription(context.getString(R.string.action_save)).performClick()
        compose.waitUntil(5_000) { saved }
        val result = saved()
        assertEquals(listOf("Measure the planting beds", "Choose spring bulbs", "Water the new beds"), result.subtasks.map { it.content })
        assertTrue(result.subtasks.first().isCompleted)
        assertEquals("choose", result.subtasks[1].id)
        assertEquals(sample.details, result.details)
    }

    @Test fun tapViewsLongPressEditsAndSelectModeDoesNotCompleteTasks() {
        val model = TaskViewModel(repository, settings, tags, ConfettiController())
        store.put("tasks", model)
        var viewed: TaskEntity? = null
        var edited: TaskEntity? = null
        compose.setContent { VerveDoTheme(dynamicColor = false, animate = false) {
            SystemBars(false)
            SharedTransitionLayout {
                val stack = remember { mutableStateListOf<NavKey>(VerveDoScreen.Tasks) }
                NavDisplay(backStack = stack, entryProvider = entryProvider {
                    entry<VerveDoScreen.Tasks> { TasksPage(toTaskAddPage = {},
                        toTaskViewPage = { viewed = it }, toTaskEditPage = { edited = it }, viewModel = model) }
                })
            }
        } }
        compose.waitUntil(5_000) { model.uiState.value.originalTaskList.size == 2 }
        capture("tasks")
        compose.onNodeWithText(sample.content).performClick()
        compose.runOnIdle { assertEquals(1, viewed?.id); assertNull(edited) }
        compose.onNodeWithText(sample.content).performTouchInput { longClick() }
        compose.runOnIdle { assertEquals(1, edited?.id) }
        compose.onNodeWithContentDescription(context.getString(R.string.task_select_accessibility)).performClick()
        compose.onAllNodes(isToggleable()).assertCountEquals(2)
        compose.onNode(hasText(sample.content) and isToggleable()).assertIsOff().performClick().assertIsOn()
        compose.onNodeWithText("Book the train tickets").performClick()
        compose.runOnIdle { assertEquals(setOf(1, 2), model.uiState.value.selectedTaskIds) }
        capture("selection")
        assertFalse(saved().isCompleted)
        compose.onNodeWithText(sample.content).performClick()
        compose.onNodeWithText("Book the train tickets").performClick()
        compose.runOnIdle { assertTrue(model.uiState.value.inSelectionMode) }
        compose.onNodeWithContentDescription(context.getString(R.string.action_delete)).assertIsNotEnabled()
        compose.onNodeWithContentDescription(context.getString(R.string.tip_clear_selected_items)).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.action_search)).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.task_select_accessibility)).assertIsDisplayed()
    }

    @Test fun deletionShowsUnavailableState() {
        showDetail()
        runBlocking { repository.deleteTask(sample) }
        compose.waitUntil(5_000) { detail.uiState.value.task == null }
        compose.onNodeWithText(context.getString(R.string.task_not_found)).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.task_detail_edit)).assertDoesNotExist()
    }

    @Test fun captureDarkLargeText() { showDetail(dark = true, scale = 1.3f); capture("detail-dark-large") }

    @Test fun longFrenchTitleAndCompletedStatusRemainReadable() {
        val longTitle = "Préparer le jardin pour l’automne et planter des herbes le long de l’allée"
        runBlocking { repository.updateTask(sample.copy(content = longTitle, isCompleted = true)) }
        showDetail(dark = true, scale = 1.3f, expectedTitle = longTitle)
        compose.onNodeWithText(longTitle).assertIsDisplayed()
        compose.onNodeWithContentDescription("Modifier la tâche").assertIsDisplayed()
        compose.onNodeWithText("Terminée").assertIsDisplayed()
        capture("detail-long-fr-completed")
        compose.onNodeWithText("Terminée").performClick()
        compose.waitUntil(5_000) { !saved().isCompleted }
        compose.onNodeWithText("À faire").assertIsDisplayed()
        compose.onNodeWithTag("task-detail-date").assertIsDisplayed()
        compose.onNode(hasScrollToNodeAction()).performScrollToIndex(0)
        capture("detail-long-fr")
    }

    @Test fun undatedStatusRemainsAvailableAndDateDoesNotToggleTask() {
        runBlocking { repository.updateTask(sample.copy(dueDateMillis = null, dueTimeMinutes = null)) }
        showDetail()
        compose.onNodeWithTag("task-detail-date").assertContentDescriptionEquals(context.getString(R.string.view_no_date)).performClick()
        assertFalse(saved().isCompleted)
        compose.onNodeWithText(context.getString(R.string.task_status_todo)).performClick()
        compose.waitUntil(5_000) { saved().isCompleted }
        compose.onNodeWithText(context.getString(R.string.task_status_completed)).assertIsDisplayed()
        capture("detail-undated-completed")
    }

    @Test fun dateInAnotherYearIsExplicitAndCompletingClearsOverdue() {
        val date = LocalDate.now().minusYears(1).withMonth(3).withDayOfMonth(6)
        runBlocking { repository.updateTask(sample.copy(dueDateMillis = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(), dueTimeMinutes = null)) }
        showDetail()
        compose.onNodeWithText(date.year.toString(), substring = true, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Overdue", substring = true, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.task_status_todo)).performClick()
        compose.waitUntil(5_000) { saved().isCompleted }
        compose.onNodeWithText("Overdue", substring = true, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun captureEmptyChecklist() {
        runBlocking { repository.updateTask(sample.copy(subtasks = emptyList(), details = "")) }
        showDetail()
        compose.onNodeWithText(context.getString(R.string.task_subtasks_add)).assertIsDisplayed()
        capture("detail-empty")
    }

    private fun showDetail(dark: Boolean = false, scale: Float = 1f, expectedTitle: String = sample.content, onEdit: (TaskEntity) -> Unit = {}) {
        compose.setContent {
            SystemBars(dark)
            val localized = remember { context.createConfigurationContext(android.content.res.Configuration(context.resources.configuration).apply {
                setLocale(if (dark) java.util.Locale.FRENCH else java.util.Locale.ENGLISH)
            }) }
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale),
                LocalContext provides localized, LocalResources provides localized.resources,
                LocalConfiguration provides localized.resources.configuration) {
                VerveDoTheme(darkTheme = dark, dynamicColor = false, animate = false) {
                    TaskDetailPage(1, {}, onEdit, detail)
                }
            }
        }
        compose.waitUntil(5_000) { !detail.uiState.value.isLoading }
        compose.waitUntil(5_000) { compose.onAllNodesWithText(expectedTitle).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun saved() = runBlocking { repository.observeTask(1).first()!! }

    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureDetail") != "true") return
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(500)
        val prefix = InstrumentationRegistry.getArguments().getString("capturePrefix") ?: "phone"
        val file = File(context.getExternalFilesDir(null), "detail-review/$prefix-$name.png")
        file.parentFile!!.mkdirs()
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Composable
    private fun SystemBars(dark: Boolean) {
        val window = LocalActivity.current!!.window
        val view = LocalView.current
        SideEffect { WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark }
    }
}
