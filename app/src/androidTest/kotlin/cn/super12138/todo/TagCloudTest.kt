package cn.super12138.todo

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModelStore
import androidx.room3.Room
import androidx.test.platform.app.InstrumentationRegistry
import cn.super12138.todo.logic.*
import cn.super12138.todo.logic.database.*
import cn.super12138.todo.logic.datastore.DataStoreManager
import cn.super12138.todo.ui.pages.collections.*
import cn.super12138.todo.ui.pages.settings.SettingsDataCategoryViewModel
import cn.super12138.todo.ui.theme.VerveDoTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.Locale

class TagCloudTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val jobs = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val models = ViewModelStore()
    private lateinit var database: TaskDatabase
    private lateinit var tasks: TaskRepository
    private lateinit var settings: SettingsRepository
    private lateinit var tags: TagRepository
    private lateinit var model: TagCloudViewModel
    private lateinit var editor: SettingsDataCategoryViewModel
    private lateinit var prefs: File
    private var opened: String? = null
    private var edited: String? = null
    private val longName = "Projects to explore when there is a little more time"
    private val purple = 0xFF7953A8.toInt()

    @Before fun setup() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(context, TaskDatabase::class.java).build()
        tasks = TaskRepository(database.taskDao())
        prefs = File(context.cacheDir, "cloud-${System.nanoTime()}.preferences_pb")
        settings = SettingsRepository(DataStoreManager(PreferenceDataStoreFactory.create(scope = jobs) { prefs }))
        tags = TagRepository(settings, tasks)
        settings.setCategories(listOf("Work", "Home", "Garden", "Books", "Travel", "Errands", longName))
        settings.setTagColor("Home", 0xFFB15B35.toInt())
        settings.setTagColor("Work", 0xFF3877B8.toInt())
        settings.setTagColor("Garden", 0xFF39846A.toInt())
        tasks.insertTask(TaskEntity("Open task", priority = 0f, tags = listOf("Home", "Work"), id = 1))
        tasks.insertTask(TaskEntity("Completed task", priority = 0f, tags = listOf("Home"), isCompleted = true, id = 2))
        tasks.insertTask(TaskEntity("Untagged task", priority = 0f, id = 3))
        tags.setFavorite("Home", true)
        model = TagCloudViewModel(tags, tasks, settings)
        models.put("cloud", model)
        editor = SettingsDataCategoryViewModel(tags, context.applicationContext as android.app.Application,
            refreshWidgets = { _, _ -> })
        models.put("editor", editor)
    }

    @After fun cleanup() {
        models.clear()
        jobs.cancel()
        database.close()
        prefs.delete()
    }

    private fun description(name: String, count: Int) = context.getString(R.string.tag_pill_description,
        name, context.resources.getQuantityString(R.plurals.tag_task_count, count, count))

    private fun show(dark: Boolean = false, french: Boolean = false, fontScale: Float = 1f,
        withDialogs: Boolean = false): StateRestorationTester {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            val window = androidx.activity.compose.LocalActivity.current!!.window
            val localized = remember {
                context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                    setLocale(if (french) Locale.FRENCH else Locale.ENGLISH)
                })
            }
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalResources provides localized.resources,
                LocalConfiguration provides localized.resources.configuration,
                LocalDensity provides Density(LocalDensity.current.density, fontScale)
            ) {
                VerveDoTheme(darkTheme = dark, dynamicColor = false, animate = false) {
                    val view = LocalView.current
                    SideEffect { androidx.core.view.WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark }
                    if (withDialogs) {
                        TagCloudPage(toTasks = { opened = it }, toSettings = {}, viewModel = model,
                            tagEditorViewModel = editor)
                    } else {
                        val state by model.state.collectAsState()
                        TagCloudContent(state, { opened = it }, model::setFavorite, model::reorderTags,
                            { edited = it }, {}, {}, model::dismissGestureHint)
                    }
                }
            }
        }
        compose.waitUntil(5_000) { model.state.value.loaded }
        return restoration
    }

    @Test fun gestureHintDismissalPersistsAndRemovesItsSpace() {
        val restoration = show()
        val hint = context.getString(R.string.tag_cloud_hint)
        val home = compose.onNodeWithContentDescription(description("Home", 2))
        compose.onNodeWithText(hint).assertIsDisplayed()
        val before = home.fetchSemanticsNode().boundsInRoot.top
        capture("hint")
        compose.onNodeWithContentDescription(context.getString(R.string.tag_cloud_hint_dismiss)).performClick()
        compose.waitUntil(5_000) { !model.state.value.showGestureHint }
        compose.onNodeWithText(hint).assertDoesNotExist()
        assertTrue(home.fetchSemanticsNode().boundsInRoot.top < before)
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(hint).assertDoesNotExist()
        capture("hint-dismissed")
        // Reopen the preferences file to verify dismissal survives an app restart.
        runBlocking {
            jobs.coroutineContext[Job]!!.cancelAndJoin()
            val reopenedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val reopened = SettingsRepository(DataStoreManager(
                    PreferenceDataStoreFactory.create(scope = reopenedScope) { prefs }))
                assertTrue(reopened.tagCloudHintDismissedFlow.first())
            } finally {
                reopenedScope.coroutineContext[Job]!!.cancelAndJoin()
            }
        }
    }

    @Test fun cloudDeleteConfirmsCancelsAndPreservesTasks() {
        val restoration = show(withDialogs = true)
        val home = compose.onNodeWithContentDescription(description("Home", 2))
        home.performTouchInput { longClick() }
        compose.onNodeWithText(context.getString(R.string.tag_delete)).assertIsDisplayed()
        capture("delete-menu")
        compose.onNodeWithText(context.getString(R.string.tag_delete)).performClick()
        compose.onNodeWithText(context.getString(R.string.tag_delete_title, "Home")).assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(context.getString(R.string.tag_delete_title, "Home")).assertIsDisplayed()
        capture("delete-confirm")
        assertNull(opened)
        compose.onNodeWithText(context.getString(R.string.action_cancel)).performClick()
        assertEquals(2, runBlocking { tags.usageCounts.first()["Home"] })
        assertTrue("Home" in runBlocking { tags.favorites.first() })
        home.assertIsDisplayed().performTouchInput { longClick() }
        compose.onNodeWithText(context.getString(R.string.tag_delete)).performClick()
        compose.onNodeWithText(context.getString(R.string.tag_delete)).performClick()
        compose.waitUntil(5_000) { "Home" !in model.state.value.tags }
        home.assertDoesNotExist()
        assertFalse("Home" in runBlocking { tags.favorites.first() })
        val savedTasks = runBlocking { tasks.getAllTasks().first() }
        assertEquals(3, savedTasks.size)
        assertEquals(listOf("Work"), savedTasks.first { it.id == 1 }.taskTags)
        assertTrue(savedTasks.first { it.id == 2 }.isCompleted)
        assertTrue(savedTasks.first { it.id == 2 }.taskTags.isEmpty())
        compose.onNodeWithContentDescription(context.getString(R.string.view_more)).performClick()
        compose.onNodeWithText("Manage tags").assertDoesNotExist()
    }

    @Test fun unusedTagDeletionIsAccessibleInFrench() {
        show(dark = true, french = true, fontScale = 1.3f, withDialogs = true)
        val books = compose.onNodeWithContentDescription("Books, 0 tâche")
        val actions = books.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.CustomActions]
        compose.runOnIdle {
            assertTrue(actions.first { it.label == "Supprimer l’étiquette" }.action())
        }
        compose.onNodeWithText("Supprimer « Books » ?").assertIsDisplayed()
        capture("delete-fr-dark-large")
        compose.onNodeWithText("Supprimer l’étiquette").performClick()
        compose.waitUntil(5_000) { "Books" !in model.state.value.tags }
        books.assertDoesNotExist()
        assertEquals(3, runBlocking { tasks.getAllTasks().first().size })
    }

    @Test fun cloudShowsTagsAlphabeticallyAndCountsAllTasks() {
        show()
        compose.onAllNodesWithContentDescription(description("Home", 2)).assertCountEquals(1)
        compose.onNodeWithText("Favourites").assertDoesNotExist()
        compose.onNodeWithText("All tags").assertDoesNotExist()
        val order = listOf("Books" to 0, "Errands" to 0, "Garden" to 0, "Home" to 2, longName to 0, "Travel" to 0, "Work" to 1)
        val bounds = order.map { (name, count) -> compose.onNodeWithContentDescription(description(name, count)).fetchSemanticsNode().boundsInRoot }
        bounds.zipWithNext().forEach { (left, right) ->
            assertTrue(left.top < right.top || left.top == right.top && left.left < right.left)
        }
        compose.onNodeWithContentDescription(description("Books", 0)).assertExists()
        compose.onNodeWithText(context.getString(R.string.view_options)).assertDoesNotExist()
        capture("light")
        compose.onNodeWithContentDescription(description("Home", 2)).performClick()
        assertEquals("Home", opened)
        compose.onNodeWithText(context.getString(R.string.widget_no_tag)).performScrollTo().performClick()
        assertEquals("", opened)
        compose.onNodeWithContentDescription(context.getString(R.string.tag_create)).performClick()
        assertEquals("", edited)
    }

    @Test fun longPressFavoritesAndEditSearchAndRestore() {
        val restoration = show()
        val work = compose.onNodeWithContentDescription(description("Work", 1))
        work.performScrollTo().performTouchInput { longClick() }
        assertNull(opened)
        compose.onNodeWithText(context.getString(R.string.tag_favorite_add)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.tag_edit)).assertIsDisplayed()
        capture("menu")
        compose.onNodeWithText(context.getString(R.string.tag_favorite_add)).performClick()
        compose.waitUntil(5_000) { "Work" in model.state.value.favorites }
        compose.onAllNodesWithContentDescription(description("Work", 1)).assertCountEquals(1)
        compose.onNodeWithContentDescription(description("Home", 2)).performScrollTo().performTouchInput { longClick() }
        compose.onNodeWithText(context.getString(R.string.tag_favorite_remove)).performClick()
        compose.waitUntil(5_000) { "Home" !in model.state.value.favorites }
        compose.onAllNodesWithContentDescription(description("Home", 2)).assertCountEquals(1)
        compose.onNodeWithContentDescription(description("Home", 2)).performTouchInput { longClick() }
        compose.onNodeWithText(context.getString(R.string.tag_edit)).performClick()
        assertEquals("Home", edited)
        compose.onNodeWithContentDescription(context.getString(R.string.tag_search)).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("work")
        restoration.emulateSavedInstanceStateRestore()
        compose.onAllNodesWithContentDescription(description("Work", 1)).assertCountEquals(1)
        compose.onNodeWithContentDescription(description("Home", 2)).assertDoesNotExist()
        compose.onNode(hasSetTextAction()).performTextReplacement("does not exist")
        compose.onNodeWithText(context.getString(R.string.tag_search_empty)).assertExists()
    }

    @Test fun favoritePersistsFollowsRenameAndDeletionAndKeepsUnusedTags() = runBlocking {
        tags.setFavorite("Work", true)
        tags.setFavorite("Garden", true)
        tags.reorderFavorites(listOf("Garden", "Home", "Work"))
        assertEquals(listOf("Garden", "Home", "Work"), TagRepository(settings, tasks).favorites.first())
        tags.save("Home", "Household", purple)
        assertEquals(listOf("Garden", "Household", "Work"), tags.favorites.first())
        tags.save("Household", "HOUSEHOLD", purple)
        assertEquals(listOf("Garden", "HOUSEHOLD", "Work"), tags.favorites.first())
        tags.delete("HOUSEHOLD")
        assertEquals(listOf("Garden", "Work"), tags.favorites.first())
        tasks.insertTask(TaskEntity("Imported", priority = 0f, tags = listOf("Imported"), id = 4))
        tags.setFavorite("Imported", true)
        tasks.deleteTaskFromIds(setOf(4))
        assertTrue("Imported" in tags.tags.first())
        assertEquals(3, tasks.getAllTasks().first().size)
    }

    @Test fun dragAndMenuReorderAllTagsAndResetRestoresAlphabeticalOrder() {
        show()
        val home = compose.onNodeWithContentDescription(description("Home", 2))
        val work = compose.onNodeWithContentDescription(description("Work", 1))
        val alphabetical = model.state.value.tags
        val displacement = work.fetchSemanticsNode().boundsInRoot.center - home.fetchSemanticsNode().boundsInRoot.center
        home.performTouchInput {
            down(center)
            advanceEventTime(650)
            moveTo(center + displacement)
        }
        compose.waitForIdle()
        capture("drag")
        home.performTouchInput { up() }
        compose.waitUntil(5_000) { model.state.value.tags.last() == "Home" }
        assertNull(opened)
        assertEquals(model.state.value.tags, runBlocking { TagRepository(settings, tasks).tags.first() })
        home.performTouchInput { longClick() }
        compose.onNodeWithText(context.getString(R.string.tag_move_earlier)).performClick()
        compose.waitUntil(5_000) { model.state.value.tags.takeLast(2) == listOf("Home", "Work") }
        compose.onAllNodesWithContentDescription(description("Home", 2)).assertCountEquals(1)
        capture("reordered")
        compose.onNodeWithContentDescription(context.getString(R.string.view_more)).performClick()
        compose.onNodeWithText(context.getString(R.string.tag_reset_order)).performClick()
        compose.waitUntil(5_000) { model.state.value.tags == alphabetical && !model.state.value.manualOrder }
    }

    @Test fun frenchDarkLargeTextKeepsLongNamesAndActionsReadable() {
        show(dark = true, french = true, fontScale = 1.3f)
        compose.onNodeWithText("Favoris").assertDoesNotExist()
        compose.onNodeWithContentDescription("Nouvelle étiquette").assertIsDisplayed()
        compose.onNodeWithContentDescription("$longName, 0 tâche").performScrollTo().assertIsDisplayed()
        capture("dark-large-fr")
        compose.onNodeWithContentDescription("$longName, 0 tâche").performTouchInput { longClick() }
        compose.onNodeWithText("Ajouter aux favoris").assertIsDisplayed()
        compose.onNodeWithText("Modifier l’étiquette").assertIsDisplayed()
    }

    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureTagCloud") != "true") return
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(300)
        val prefix = InstrumentationRegistry.getArguments().getString("capturePrefix") ?: "phone"
        val file = File(context.getExternalFilesDir(null), "tag-cloud/$prefix-$name.png")
        file.parentFile!!.mkdirs()
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
