package cn.super12138.todo

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModelStore
import androidx.room3.Room
import androidx.test.platform.app.InstrumentationRegistry
import cn.super12138.todo.logic.*
import cn.super12138.todo.logic.database.*
import cn.super12138.todo.logic.datastore.DataStoreManager
import cn.super12138.todo.logic.model.*
import cn.super12138.todo.ui.pages.collections.*
import cn.super12138.todo.ui.theme.VerveDoTheme
import cn.super12138.todo.ui.widget.upcoming.labelRes
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

class TaskCollectionUiTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val jobs = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = ViewModelStore()
    private lateinit var database: TaskDatabase
    private lateinit var tasks: TaskRepository
    private lateinit var views: TaskViewsRepository
    private lateinit var tags: TagRepository
    private lateinit var settings: SettingsRepository
    private lateinit var prefs: File
    private lateinit var model: TaskCollectionViewModel
    private val today = LocalDate.now()
    private fun task(id: Int, title: String, days: Long?, tags: List<String>, time: Int? = null, priority: Float = 0f) =
        TaskEntity(title, priority = priority, id = id, tags = tags,
            dueDateMillis = days?.let { today.plusDays(it).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() },
            dueTimeMinutes = time)

    @Before fun setup() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(context, TaskDatabase::class.java).build()
        tasks = TaskRepository(database.taskDao())
        prefs = File(context.cacheDir, "collections-" + System.nanoTime() + ".preferences_pb")
        val ds = PreferenceDataStoreFactory.create(scope = jobs) { prefs }
        settings = SettingsRepository(DataStoreManager(ds))
        views = TaskViewsRepository(ds)
        tags = TagRepository(settings, tasks)
        listOf(
            task(1, "Review the kitchen drawings", 0, listOf("Home", "Work"), 630, 1f).copy(subtasks = listOf(
                Subtask("Check dimensions", true, "one"), Subtask("Review finishes", false, "two"))),
            task(2, "Prepare the autumn garden", 0, listOf("Home", "Garden"), 870),
            task(3, "Book the train tickets", 0, listOf("Personal")),
            task(4, "Send the invoice", 1, listOf("Work"), 540),
            task(5, "Renew home insurance", -2, listOf("Home"), priority = 1f),
            task(6, "Collect the parcel", 2, listOf("Personal"), 1020),
            task(7, "Read the project brief", 3, listOf("Work")),
            task(8, "Repair the shelf", null, listOf("Home")),
            task(9, "Book a dental appointment", 7, listOf("Personal")),
            task(10, "Service the bicycle", 20, emptyList())
        ).forEach { tasks.insertTask(it) }
        settings.setTagColor("Home", 0xFFB15C30.toInt())
        settings.setTagColor("Work", 0xFF397BC0.toInt())
        settings.setTagColor("Garden", 0xFF3C8054.toInt())
        settings.setTagColor("Personal", 0xFF8155A0.toInt())
        views.update(TaskViewMode.Agenda) { it.copy(collapsed = setOf("overdue", "this-week", "next-week", "later", "no-date")) }
        views.update(TaskViewMode.Tags) { it.copy(tagOrder = listOf("Work", "Home"), collapsed = setOf("tag:Garden", "tag:Personal")) }
    }

    @After fun cleanup() {
        store.clear()
        jobs.cancel()
        database.close()
        prefs.delete()
    }

    private fun show(mode: TaskViewMode = TaskViewMode.Agenda, dark: Boolean = false, fontScale: Float = 1f,
        tagScope: String? = null, french: Boolean = false, onAdjacent: (String) -> Unit = {}) {
        val initial = tagScope?.let { TaskViewConfig(mode = TaskViewMode.Tags, tags = setOf(it), status = TaskStatusFilter.All).encode() }
        model = TaskCollectionViewModel(mode, initial, tasks, tags, views, context, refreshWidgets = {})
        store.put("view", model)
        compose.setContent {
            val window = androidx.activity.compose.LocalActivity.current!!.window
            val localized = remember {
                context.createConfigurationContext(android.content.res.Configuration(context.resources.configuration).apply {
                    setLocale(if (french) java.util.Locale.FRENCH else java.util.Locale.ENGLISH)
                })
            }
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale),
                LocalContext provides localized, LocalResources provides localized.resources,
                LocalConfiguration provides localized.resources.configuration) {
                VerveDoTheme(darkTheme = dark, dynamicColor = false, animate = false) {
                    val view = LocalView.current
                    SideEffect { androidx.core.view.WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark }
                    TaskCollectionPage(mode, {}, {}, {}, {}, initialConfiguration = initial,
                        onBack = if (tagScope != null) ({}) else null, tagScope = tagScope, toAdjacentTag = if (tagScope != null) onAdjacent else null, viewModel = model)
                }
            }
        }
        compose.waitUntil(5_000) { model.state.value.loaded }
        compose.waitForIdle()
    }

    @Test fun agendaCollapseCompleteUndoAndSavedOptions() {
        show()
        compose.onNodeWithText(context.getString(R.string.view_overdue)).assertIsDisplayed()
        compose.onNodeWithText("Review the kitchen drawings").assertIsDisplayed()
        capture("agenda")
        compose.onNodeWithText(context.getString(R.string.time_today)).performClick()
        compose.onNodeWithText("Review the kitchen drawings").assertDoesNotExist()
        compose.waitUntil(5_000) { "day:$today" in model.state.value.config.collapsed }
        compose.onNodeWithText(context.getString(R.string.time_today)).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.widget_complete_task, "Review the kitchen drawings")).performClick()
        compose.waitUntil(5_000) { runBlocking { tasks.observeTask(1).first()!!.isCompleted } }
        compose.onNodeWithText(context.getString(R.string.widget_undo)).performClick()
        compose.waitUntil(5_000) { !runBlocking { tasks.observeTask(1).first()!!.isCompleted } }
        compose.onNodeWithContentDescription(context.getString(R.string.view_more)).performClick()
        compose.onNodeWithText(context.getString(R.string.view_options)).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.view_increase_days)).performClick()
        compose.waitUntil(5_000) { model.state.value.config.individualDays == 4 }
        compose.onNodeWithText(context.getString(R.string.view_this_week_toggle)).performClick()
        compose.waitUntil(5_000) { !model.state.value.config.thisWeek }
        capture("options")
        assertEquals(4, runBlocking { views.observe(TaskViewMode.Agenda).first().individualDays })
    }

    @Test fun tagsRepeatTaskButSearchAndSelectionUseUniqueIdentities() {
        show(TaskViewMode.Tags)
        compose.onAllNodesWithText("Review the kitchen drawings").assertCountEquals(2)
        capture("tags")
        compose.onNodeWithContentDescription(context.getString(R.string.action_search)).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("kitchen")
        compose.onAllNodesWithText("Review the kitchen drawings").assertCountEquals(1)
        compose.onNodeWithContentDescription(context.getString(R.string.view_select)).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.tip_select_all)).performClick()
        compose.onNodeWithText(context.getString(R.string.title_selected_count, 1)).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.action_delete)).performClick()
        compose.onNodeWithText(context.getString(R.string.tip_delete_task, 1)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.action_cancel)).performClick()
        assertFalse(runBlocking { tasks.observeTask(1).first()!!.isCompleted })
    }

    @Test fun completionFilterSharedAcrossTabsAndPastTasksStayVisible() {
        runBlocking { tasks.completeTask(5); views.update(TaskViewMode.Agenda) { it.copy(status = TaskStatusFilter.Completed) } }
        show(TaskViewMode.Tags)
        assertEquals(TaskStatusFilter.Completed, model.state.value.config.status)
        compose.onNodeWithText("Renew home insurance").assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.widget_restore_task, "Renew home insurance")).performClick()
        compose.waitUntil(5_000) { !runBlocking { tasks.observeTask(5).first()!!.isCompleted } }
        compose.onNodeWithText("Renew home insurance").assertDoesNotExist()
    }

    @Test fun darkLargeTextKeepsActionsReadable() {
        show(dark = true, fontScale = 1.3f)
        compose.onNodeWithText("Review the kitchen drawings").assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.widget_complete_task, "Review the kitchen drawings")).assertIsDisplayed()
        capture("agenda-dark-large")
    }

    @Test fun categoryIconsStayBesideTitleWithCompactOverflow() = runBlocking {
        val assigned = listOf("Home", "Work", "Garden", "Personal")
        val liveTags = org.koin.core.context.GlobalContext.get().get<TagRepository>()
        listOf("house", "work", "tree", "rock").forEachIndexed { index, icon ->
            val name = assigned[index]
            liveTags.save(name.takeIf { it in liveTags.tags.first() }, name, 0xff39846a.toInt(), icon)
        }
        tasks.insertTask(task(11, "Review", 0, assigned))
        show(dark = InstrumentationRegistry.getArguments().getString("dark") == "true",
            fontScale = if (InstrumentationRegistry.getArguments().getString("dark") == "true") 1.3f else 1f)
        compose.onNodeWithText("Review").performScrollTo().assertIsDisplayed()
        val title = compose.onNodeWithText("Review", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val overflow = compose.onNodeWithText("+2", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("Category overflow must be to the right of the title", overflow.left >= title.right)
        assertTrue("Icons must share the title row", overflow.center.y >= title.top && overflow.center.y <= title.bottom)
        compose.onNodeWithText("+2", useUnmergedTree = true).assertIsDisplayed()
        capture("compact-icons")
    }

    @Test fun scopedTagHasOneHeadingAndKeepsFiltersWithinItsTasks() {
        show(TaskViewMode.Tags, tagScope = "Home")
        compose.onAllNodesWithText("Home").assertCountEquals(1)
        compose.onNodeWithText(context.getString(R.string.view_tags)).assertDoesNotExist()
        compose.onNodeWithText("4 tasks").assertIsDisplayed()
        compose.onNodeWithText("Send the invoice").assertDoesNotExist()
        capture("tag-detail")
        compose.onNodeWithText(context.getString(R.string.view_all)).performClick()
        compose.onNodeWithText(context.getString(R.string.view_completed)).performClick()
        compose.onNodeWithText(context.getString(R.string.view_empty)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.view_clear_filters)).performClick()
        compose.waitUntil(5_000) { model.state.value.config.status == TaskStatusFilter.All }
        assertEquals(setOf("Home"), model.state.value.config.tags)
        compose.onNodeWithText("4 tasks").assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.action_search)).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("not a matching task")
        compose.onNodeWithText(context.getString(R.string.view_clear_filters)).performClick()
        compose.onNodeWithText("4 tasks").assertIsDisplayed()
        compose.onNodeWithText("Send the invoice").assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.view_select)).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.tip_select_all)).performClick()
        compose.onNodeWithText(context.getString(R.string.title_selected_count, 4)).assertIsDisplayed()
    }

    @Test fun scopedTagKeepsSortingCompletionAndUndo() {
        show(TaskViewMode.Tags, tagScope = "Home")
        compose.onNodeWithText(context.getString(R.string.view_sort_soonest)).performClick()
        compose.onNodeWithText(context.getString(TaskGroupSort.Alphabetical.labelRes())).performClick()
        compose.waitUntil(5_000) { model.state.value.config.sort == TaskGroupSort.Alphabetical }
        compose.onNodeWithContentDescription(context.getString(R.string.widget_complete_task, "Review the kitchen drawings")).performClick()
        compose.waitUntil(5_000) { runBlocking { tasks.observeTask(1).first()!!.isCompleted } }
        compose.onNodeWithText(context.getString(R.string.widget_undo)).performClick()
        compose.waitUntil(5_000) { !runBlocking { tasks.observeTask(1).first()!!.isCompleted } }
        assertEquals(setOf("Home"), model.state.value.config.tags)
    }

    @Test fun scopedTagLongTitleIsReadableInFrenchDarkLargeText() {
        val tag = "Projets pour la maison et le jardin"
        runBlocking { tags.save("Home", tag, 0xFFB15C30.toInt()) }
        show(TaskViewMode.Tags, dark = true, fontScale = 1.3f, tagScope = tag, french = true)
        compose.onNodeWithText(tag).assertIsDisplayed()
        compose.onNodeWithText("4 tâches").assertIsDisplayed()
        compose.onNodeWithContentDescription("Ajouter une tâche").assertIsDisplayed()
        capture("tag-detail-fr-dark-large")
    }

    @Test fun emptyTagStaysScopedWithoutResetAction() {
        runBlocking { settings.setCategories(listOf("Someday")) }
        show(TaskViewMode.Tags, tagScope = "Someday")
        compose.onNodeWithText("Someday").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.tag_tasks_empty)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.view_clear_filters)).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.action_add_task)).assertIsDisplayed()
        capture("tag-detail-empty")
    }

    @Test fun noTagScopeShowsOnlyUntaggedTasks() {
        show(TaskViewMode.Tags, tagScope = "")
        compose.onNodeWithText(context.getString(R.string.widget_no_tag)).assertIsDisplayed()
        compose.onNodeWithText("Service the bicycle").assertIsDisplayed()
        compose.onNodeWithText("Review the kitchen drawings").assertDoesNotExist()
    }

    @Test fun newTaskInheritsTagWithoutQuickAddMode() {
        val editor = cn.super12138.todo.ui.pages.editor.EditorViewModel(null, tasks, settings, tags,
            cn.super12138.todo.utils.ConfettiController())
        store.put("editor", editor)
        var saved by mutableStateOf(false)
        compose.setContent { VerveDoTheme(dynamicColor = false, animate = false) {
            cn.super12138.todo.ui.pages.editor.TaskEditorPage(initialCategory = "Home", viewModel = editor,
                onSaved = { saved = true })
        } }
        compose.waitUntil(5_000) { editor.uiState.value.tags == listOf("Home") }
        compose.onNode(hasSetTextAction() and hasText(context.getString(R.string.placeholder_add_todo)))
            .performTextInput("A new Home task")
        compose.onNodeWithContentDescription(context.getString(R.string.action_save)).performClick()
        compose.waitUntil(5_000) { saved }
        val created = runBlocking { tasks.getAllTasks().first().first { it.content == "A new Home task" } }
        assertEquals(listOf("Home"), created.taskTags)
    }

    @Test fun bulkEditorChangesOnlyCheckedFields() {
        show()
        compose.onNodeWithContentDescription(context.getString(R.string.view_select)).performClick()
        compose.onNodeWithText("Review the kitchen drawings").performClick()
        compose.onNodeWithText("Prepare the autumn garden").performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.bulk_edit)).performClick()
        compose.onNodeWithText(context.getString(R.string.bulk_change_tags)).performClick()
        compose.onNode(hasText("Personal") and androidx.compose.ui.test.SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Checkbox)).performClick()
        capture("bulk-editor")
        compose.onNodeWithText(context.getString(R.string.bulk_apply)).performClick()
        compose.waitUntil(5_000) { runBlocking { tasks.observeTask(1).first()!!.tags.contains("Personal") } }
        runBlocking {
            assertEquals(listOf("Home", "Garden", "Personal"), tasks.observeTask(2).first()!!.tags)
            assertEquals(1f, tasks.observeTask(1).first()!!.priority)
            assertEquals(630, tasks.observeTask(1).first()!!.dueTimeMinutes)
            assertEquals(listOf("Work"), tasks.observeTask(4).first()!!.tags)
        }
    }

    @Test fun tagSwipesAndButtonsVisitAdjacentTags() {
        var destination: String? = null
        show(TaskViewMode.Tags, tagScope = "Home", onAdjacent = { destination = it })
        compose.onNodeWithText("Review the kitchen drawings").performTouchInput { swipeLeft() }
        compose.runOnIdle { assertEquals("Personal", destination); destination = null }
        compose.onNodeWithContentDescription(context.getString(R.string.tag_previous)).performClick()
        compose.runOnIdle { assertEquals("Garden", destination) }
        capture("tag-navigation")
    }

    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureCollections") != "true") return
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(300)
        val prefix = InstrumentationRegistry.getArguments().getString("capturePrefix") ?: "phone"
        val file = File(context.getExternalFilesDir(null), "collections-review/$prefix-$name.png")
        file.parentFile!!.mkdirs()
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
