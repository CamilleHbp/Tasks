package cn.super12138.todo

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.core.view.WindowCompat
import androidx.compose.runtime.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.input.key.Key
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
import cn.super12138.todo.logic.model.Priority
import cn.super12138.todo.ui.pages.editor.*
import cn.super12138.todo.ui.theme.VerveDoTheme
import cn.super12138.todo.utils.ConfettiController
import cn.super12138.todo.utils.toLocalDateString
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

class TaskEditingUiTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val jobs = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = ViewModelStore()
    private lateinit var database: TaskDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var model: EditorViewModel
    private lateinit var prefs: File
    private var saved = false
    private val sample = TaskEntity("Review the household budget", priority = 0f, id = 501,
        details = "Check this month’s spending and plan next month’s savings.", tags = listOf("Home"))

    @Before fun setup() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(context, TaskDatabase::class.java).build()
        val repo = TaskRepository(database.taskDao())
        prefs = File(context.cacheDir, "editing-${System.nanoTime()}.preferences_pb")
        settings = SettingsRepository(DataStoreManager(PreferenceDataStoreFactory.create(scope = jobs) { prefs }))
        val tags = TagRepository(settings, repo)
        repo.insertTask(sample)
        tags.save("Home", "Home", 0xFFB15B35.toInt())
        tags.save(null, "Work", 0xFF3877B8.toInt())
        model = EditorViewModel(sample, repo, settings, tags, ConfettiController())
        store.put("editor", model)
    }

    @After fun cleanup() { store.clear(); jobs.cancel(); database.close(); prefs.delete() }

    private fun show(frenchDark: Boolean = false, realKeyboard: Boolean = false, task: TaskEntity = sample) {
        compose.setContent {
            val window = LocalActivity.current!!.window
            val view = LocalView.current
            SideEffect {
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !frenchDark
            }
            val localized = remember { context.createConfigurationContext(android.content.res.Configuration(context.resources.configuration).apply {
                setLocale(if (frenchDark) java.util.Locale.FRENCH else java.util.Locale.ENGLISH)
            }) }
            CompositionLocalProvider(LocalContext provides localized, LocalResources provides localized.resources,
                LocalConfiguration provides localized.resources.configuration,
                LocalDensity provides Density(LocalDensity.current.density, if (frenchDark) 1.3f else 1f),
                LocalSoftwareKeyboardController provides if (realKeyboard) LocalSoftwareKeyboardController.current else object : SoftwareKeyboardController {
                    override fun show() {}
                    override fun hide() {}
                }) {
                VerveDoTheme(darkTheme = frenchDark, dynamicColor = false, animate = false) {
                    TaskEditorPage(task = task, viewModel = model, onSaved = { saved = true })
                }
            }
        }
        compose.waitUntil(5_000) { model.uiState.value.categoryList.size == 2 }
    }

    @Test fun draggingEditorTagsPersistsManualOrderWithoutChangingSelection() {
        show()
        val preview = hasAnyAncestor(hasTestTag("editor-tags-preview"))
        val home = compose.onNode(hasText("Home") and preview)
        val work = compose.onNode(hasText("Work") and preview)
        home.performScrollTo().assertIsSelected()
        fun precedes(first: SemanticsNodeInteraction, second: SemanticsNodeInteraction): Boolean {
            val a = first.fetchSemanticsNode().boundsInRoot
            val b = second.fetchSemanticsNode().boundsInRoot
            return a.top < b.top || a.top == b.top && a.left < b.left
        }
        assertTrue(precedes(home, work))
        val displacement = work.fetchSemanticsNode().boundsInRoot.center - home.fetchSemanticsNode().boundsInRoot.center
        home.performTouchInput {
            down(center)
            advanceEventTime(650)
            moveTo(center + displacement)
            up()
        }
        compose.waitUntil(5_000) { precedes(work, home) }
        assertEquals(listOf("Work", "Home"), runBlocking { settings.tagOrderFlow.first() })
        home.assertIsSelected()
        runBlocking { settings.setTagOrder(emptyList()) }
        compose.waitUntil(5_000) { precedes(home, work) }
        home.assertIsSelected()
    }

    @Test fun rolloverDefaultsOffAndNewTasksInheritSettingWithOverride() = runBlocking {
        assertFalse(settings.rolloverDefaultFlow.first())
        settings.setRolloverDefault(true)
        val repo = TaskRepository(database.taskDao())
        val fresh = EditorViewModel(null, repo, settings, TagRepository(settings, repo), ConfettiController())
        store.put("fresh", fresh)
        val collect = jobs.launch { fresh.uiState.collect {} }
        fresh.setCreationDefaults("", null)
        withTimeout(5_000) { fresh.uiState.first { it.rollover } }
        fresh.setRollover(false)
        fresh.setContentText("Opt out")
        fresh.saveNewTask()
        withTimeout(5_000) { fresh.uiState.first { it.isSaved } }
        assertFalse(database.taskDao().getTasksForTagUpdate().first { it.content == "Opt out" }.rollover)
        collect.cancel()
    }

    @Test fun rolloverTogglePersistsAndIsProtectedAsDraft() {
        show()
        compose.onNodeWithTag("editor-rollover").performScrollTo().assertIsOff().performClick().assertIsOn()
        compose.runOnIdle { assertTrue(model.isModified()); model.saveNewTask() }
        compose.waitUntil(5_000) { model.uiState.value.isSaved }
        runBlocking { assertTrue(database.taskDao().getTask(sample.id)!!.rollover) }
        capture("rollover-enabled")
    }

    @Test fun rolloverFrenchDarkLarge() {
        show(frenchDark = true)
        compose.onNodeWithTag("editor-rollover").performScrollTo().assertIsOff()
        compose.onNodeWithText("Reporter au lendemain").assertIsDisplayed()
        capture("rollover-french-dark-large")
    }

    @Test fun quickDatesCloudPriorityAndRecurrenceSaveTogether() {
        show()
        capture("editor-top")
        compose.onNode(hasText("Work") and hasAnyAncestor(hasTestTag("editor-tags-preview"))).performScrollTo().performClick()
        compose.onNode(hasText("Home") and hasAnyAncestor(hasTestTag("editor-tags-preview"))).performClick()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("editor-priority"))
        compose.onNodeWithTag("editor-priority-slider").assertIsDisplayed()
            .performTouchInput { click(androidx.compose.ui.geometry.Offset(width - 1f, center.y)) }
        compose.runOnIdle { assertEquals(Priority.Urgent, model.uiState.value.priority) }
        compose.onNodeWithTag("editor-priority-slider").performTouchInput { swipeLeft() }
        compose.runOnIdle { assertEquals(Priority.fromFloat(-2f), model.uiState.value.priority) }
        compose.onNodeWithTag("editor-priority-slider").performTouchInput { swipeRight() }
        compose.runOnIdle { assertEquals(Priority.Urgent, model.uiState.value.priority) }
        compose.onNodeWithText(context.getString(R.string.time_tomorrow)).performScrollTo().performClick()
        val expectedDay = LocalDate.now().plusDays(1)
        compose.runOnIdle {
            assertEquals(expectedDay, java.time.Instant.ofEpochMilli(model.uiState.value.dueDateMillis!!).atZone(ZoneId.systemDefault()).toLocalDate())
            assertEquals(model.uiState.value.defaultDueTimeMinutes, model.uiState.value.dueTimeMinutes)
        }
        // Opening the custom picker after a quick choice must preserve that choice and month.
        val chosenDate = model.uiState.value.dueDateMillis
        compose.onNodeWithTag("editor-date-time").performScrollTo().performClick()
        capture("date-time-picker")
        compose.onNodeWithText(context.getString(R.string.action_confirm)).performClick()
        compose.runOnIdle { assertEquals(chosenDate, model.uiState.value.dueDateMillis) }
        compose.onNodeWithTag("editor-recurrence").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.editor_repeat_every)).performClick()
        compose.onNodeWithTag("editor-repeat-interval").performTextReplacement("2")
        compose.onNodeWithTag("editor-repeat-unit").performClick()
        compose.onNodeWithText(context.getString(R.string.editor_repeat_unit_weeks)).performClick()
        capture("recurrence-custom")
        compose.onNodeWithText(context.getString(R.string.action_confirm)).performClick()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("editor-priority"))
        capture("editor-metadata")
        compose.onNodeWithContentDescription(context.getString(R.string.action_save)).performClick()
        compose.waitUntil(5_000) { saved }
        val updated = runBlocking { database.taskDao().getTask(sample.id)!! }
        assertEquals(listOf("Work"), updated.tags)
        assertEquals(2f, updated.priority)
        assertEquals(2, TaskRecurrence.decode(updated.recurrence)!!.interval)
        assertEquals(RepeatFrequency.Weekly, TaskRecurrence.decode(updated.recurrence)!!.frequency)
    }

    @Test fun frenchDarkLargeTextKeepsEditorControlsReadable() {
        model.addSubtasks("Check recurring charges\nSet the savings target")
        model.setDueDate(LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
        model.setRecurrence(TaskRecurrence(RepeatFrequency.Monthly, anchor = LocalDate.now().toString()))
        show(frenchDark = true)
        capture("editor-fr-dark-large-top")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("editor-priority"))
        capture("editor-fr-dark-large-metadata")
        compose.onNodeWithTag("editor-recurrence").performScrollTo().performClick()
        compose.onNodeWithTag("editor-repeat-interval").assertTextContains("1")
        compose.onNodeWithTag("editor-repeat-unit").performClick()
        compose.onNodeWithText("Semaines").performClick()
        compose.onNodeWithText("Répéter").assertIsDisplayed()
        capture("recurrence-fr-dark-large")
        compose.onNodeWithText("Annuler").performClick()
        compose.onNodeWithTag("editor-date-time").performScrollTo().performClick()
        compose.onNodeWithTag("editor-include-time").performScrollTo().assertIsDisplayed()
        if (context.resources.configuration.screenWidthDp < 360) {
            compose.onNodeWithContentDescription("Choisir dans le calendrier").assertDoesNotExist()
            compose.onNodeWithContentDescription("Saisir une date").assertDoesNotExist()
        }
        capture("date-time-fr-dark-large")
    }

    @Test fun keyboardKeepsComposerAndSaveVisibleAfterRepeatedEntry() {
        model.addSubtasks((1..12).joinToString("\n") { "Existing step $it" })
        val french = InstrumentationRegistry.getArguments().getString("testLocale") == "fr"
        show(frenchDark = french, realKeyboard = true)
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("subtask-composer"))
        val composer = compose.onNodeWithTag("subtask-composer")
        composer.performScrollTo().performClick().performTextInput("First new step")
        composer.performImeAction()
        composer.assertIsFocused().assertIsDisplayed()
        composer.performTextInput("Second new step")
        composer.performImeAction()
        composer.assertIsFocused().assertIsDisplayed()
        compose.onNodeWithContentDescription(if (french) "Enregistrer" else context.getString(R.string.action_save)).assertIsDisplayed()
        capture("editor-keyboard")
        compose.runOnIdle { assertEquals(14, model.uiState.value.subtasks.size) }
    }

    @Test fun saveValidatesTitleAndPreservesOpenStatus() {
        show()
        compose.onAllNodes(isToggleable() and !hasTestTag("editor-rollover")).assertCountEquals(0)
        val title = compose.onNode(hasSetTextAction() and hasText(context.getString(R.string.placeholder_add_todo)))
        title.performTextClearance()
        compose.onNodeWithContentDescription(context.getString(R.string.action_save)).performClick()
        title.assertIsFocused()
        compose.runOnIdle { assertFalse(model.uiState.value.isCompleted); assertFalse(saved) }
        title.performTextReplacement("review the revised household budget")
        compose.onNodeWithTag("subtask-composer").performScrollTo().performTextInput("Include the final receipt")
        compose.onNodeWithContentDescription(context.getString(R.string.action_save)).performClick()
        compose.waitUntil(5_000) { saved }
        val updated = runBlocking { database.taskDao().getTask(sample.id)!! }
        assertFalse(updated.isCompleted)
        assertEquals("Review the revised household budget", updated.content)
        assertEquals(listOf("Include the final receipt"), updated.subtasks.map { it.content })
    }

    @Test fun editingCompletedTaskPreservesItsStatus() {
        val completed = sample.copy(isCompleted = true)
        runBlocking { TaskRepository(database.taskDao()).insertTask(completed) }
        model.setCompleted(true)
        model.setDetailsText("New information for the completed task")
        show(task = completed)
        compose.onAllNodes(isToggleable()).assertCountEquals(0)
        compose.onNodeWithContentDescription(context.getString(R.string.action_save)).assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { saved }
        val updated = runBlocking { database.taskDao().getTask(sample.id)!! }
        assertTrue(updated.isCompleted)
        assertEquals("New information for the completed task", updated.details)
    }

    @Test fun shiftEnterPreservesLineBreakAndEnterCreatesOneSubtask() {
        show()
        val composer = compose.onNodeWithTag("subtask-composer")
        composer.performScrollTo().performClick().performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertTrue(model.uiState.value.subtasks.isEmpty()) }
        composer.performTextInput("First line")
        composer.performKeyInput { keyDown(Key.ShiftLeft); pressKey(Key.Enter); keyUp(Key.ShiftLeft) }
        compose.runOnIdle { assertTrue(model.uiState.value.subtasks.isEmpty()) }
        composer.performTextInput("Second line")
        composer.performKeyInput { pressKey(Key.Enter) }
        composer.assertIsFocused()
        compose.runOnIdle { assertEquals(listOf("First line\nSecond line"), model.uiState.value.subtasks.map { it.content }) }
        composer.performTextInput("Another step")
        composer.performKeyInput { pressKey(Key.NumPadEnter) }
        compose.runOnIdle { assertEquals(2, model.uiState.value.subtasks.size) }
    }

    @Test fun enterAndPasteKeepComposerReadyAndUndoPreservesOtherSteps() {
        show()
        val composer = compose.onNodeWithTag("subtask-composer")
        composer.performScrollTo().performTextInput("Check recurring charges")
        composer.performImeAction()
        composer.assertIsFocused().assertTextContains("")
        compose.runOnIdle { assertEquals(listOf("Check recurring charges"), model.uiState.value.subtasks.map { it.content }) }
        composer.performTextInput("- Categorize transactions\n- Set the savings target\n")
        compose.waitUntil(5_000) { model.uiState.value.subtasks.size == 3 }
        composer.assertIsFocused()
        compose.onNodeWithText(context.getString(R.string.widget_undo)).performClick()
        compose.waitUntil(5_000) { model.uiState.value.subtasks.size == 1 }
        composer.performTextInput("Review savings goals")
        composer.performImeAction()
        capture("editor-checklist")
        compose.onNodeWithContentDescription(context.getString(R.string.task_subtask_remove, 1)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.widget_undo)).performClick()
        compose.waitUntil(5_000) { model.uiState.value.subtasks.size == 2 }
        compose.runOnIdle { assertEquals("Check recurring charges", model.uiState.value.subtasks.first().content) }
        composer.performScrollTo().performTextInput("Keep this unfinished input")
        compose.onNodeWithContentDescription(context.getString(R.string.action_save)).performClick()
        compose.waitUntil(5_000) { saved }
        val updated = runBlocking { database.taskDao().getTask(sample.id)!! }
        assertEquals(listOf("Check recurring charges", "Review savings goals", "Keep this unfinished input"), updated.subtasks.map { it.content })
    }

    @Test fun emptyTitleReturnsFocusAndNumericIntervalPreservesAnchor() {
        model.setDueDate(LocalDate.of(2026, 1, 31).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
        model.setRecurrence(TaskRecurrence(RepeatFrequency.Monthly, anchor = "2026-01-31"))
        show()
        compose.onNodeWithTag("editor-recurrence").performScrollTo().performClick()
        compose.onNodeWithTag("editor-repeat-interval")
            .performTextReplacement("30")
        compose.onNodeWithText(context.getString(R.string.action_confirm)).performClick()
        compose.runOnIdle {
            assertEquals(30, model.uiState.value.recurrence!!.interval)
            assertEquals("2026-01-31", model.uiState.value.recurrence!!.anchor)
        }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(context.getString(R.string.placeholder_add_todo)))
        compose.onNode(hasSetTextAction() and hasText(context.getString(R.string.placeholder_add_todo)))
            .performScrollTo().performTextClearance()
        compose.onNodeWithTag("editor-recurrence").performScrollTo()
        compose.onNodeWithContentDescription(context.getString(R.string.action_save)).performClick()
        compose.onNode(hasSetTextAction() and hasText(context.getString(R.string.placeholder_add_todo)))
            .assertIsDisplayed().assertIsFocused()
        compose.onNodeWithText(context.getString(R.string.error_no_content_entered)).assertIsDisplayed()
    }

    @Test fun scheduleAndRecurrenceCancelWithoutChangingDraftAndClearTogether() {
        val due = LocalDate.of(2026, 2, 28).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        model.setSchedule(due, 14 * 60 + 30)
        model.setRecurrence(TaskRecurrence(RepeatFrequency.Monthly, anchor = "2026-01-31"))
        show()
        compose.onNodeWithTag("editor-date-time").performScrollTo().performClick()
        showTimeInput()
        compose.onNodeWithTag("editor-hour").performScrollTo().performTextReplacement("25")
        compose.onNodeWithText(context.getString(R.string.action_confirm)).assertIsNotEnabled()
        showTimeInput()
        compose.onNodeWithTag("editor-hour").performTextReplacement("16")
        compose.onNodeWithTag("editor-minute").performTextReplacement("45")
        compose.onNodeWithTag("editor-hour").assertTextContains("16")
        compose.onNodeWithTag("editor-minute").assertTextContains("45")
        compose.onNodeWithTag("editor-include-time").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.action_cancel)).performClick()
        compose.runOnIdle { assertEquals(870, model.uiState.value.dueTimeMinutes) }
        compose.onNodeWithTag("editor-recurrence").performScrollTo().performClick()
        compose.onNodeWithTag("editor-repeat-interval").performTextReplacement("0")
        compose.onNodeWithText(context.getString(R.string.action_confirm)).assertIsNotEnabled()
        compose.onNodeWithTag("editor-repeat-interval").performTextReplacement("999")
        compose.onNodeWithText(context.getString(R.string.action_cancel)).performClick()
        compose.runOnIdle { assertEquals(1, model.uiState.value.recurrence!!.interval) }
        compose.onNodeWithTag("editor-date-time").performScrollTo().performClick()
        compose.onNodeWithTag("editor-include-time").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.action_confirm)).performClick()
        compose.runOnIdle {
            assertNull(model.uiState.value.dueTimeMinutes)
            assertEquals("2026-01-31", model.uiState.value.recurrence!!.anchor)
        }
        compose.onNodeWithContentDescription(context.getString(R.string.remove_due_date)).performScrollTo().performClick()
        compose.runOnIdle {
            assertNull(model.uiState.value.dueDateMillis)
            assertNull(model.uiState.value.dueTimeMinutes)
            assertNull(model.uiState.value.recurrence)
        }
    }

    @Test fun undatedRecurrenceOffersDateAndPrioritySliderRemainsAdjustable() {
        show()
        compose.onNodeWithTag("editor-recurrence").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.repeat_needs_date)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.choose_date)).performClick()
        compose.onNodeWithText(context.getString(R.string.action_confirm)).performClick()
        compose.onNodeWithText(context.getString(R.string.editor_repeat_every)).performClick()
        compose.onNodeWithTag("editor-repeat-unit").performClick()
        compose.onNodeWithText(context.getString(R.string.editor_repeat_unit_years)).performClick()
        compose.onNodeWithTag("editor-repeat-interval").performTextReplacement("3")
        compose.onNodeWithText(context.getString(R.string.action_confirm)).performClick()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("editor-priority-slider"))
        compose.onNodeWithTag("editor-priority-slider").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(-1f) }
        compose.runOnIdle {
            assertEquals(RepeatFrequency.Yearly, model.uiState.value.recurrence!!.frequency)
            assertEquals(3, model.uiState.value.recurrence!!.interval)
            assertEquals(Priority.NotImportant, model.uiState.value.priority)
        }
    }

    @Test fun overflowExpandsInlineAndStaysExpandedAfterSearchAndScrolling() {
        repeat(15) { model.addTag("Project collection ${it + 1}") }
        val longTag = "A very long household renovation and planning tag"
        model.addTag(longTag)
        show()
        compose.onNodeWithTag("editor-more-tags").performScrollTo().assertIsDisplayed()
        capture("tags-two-rows")
        compose.onNodeWithTag("editor-more-tags").performClick()
        compose.onNodeWithTag("editor-more-tags").assertDoesNotExist()
        compose.onNode(hasText("Project collection 9") and hasAnyAncestor(hasTestTag("editor-tags-preview"))).performScrollTo().assertIsSelected()
        capture("tags-expanded")
        compose.onNodeWithContentDescription(context.getString(R.string.editor_find_tag)).performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText(context.getString(R.string.editor_find_tag)))
            .performTextReplacement("renovation")
        compose.onNode(hasText(longTag) and hasAnyAncestor(hasTestTag("editor-tags-options")))
            .performScrollTo().assertIsSelected()
        capture("tags-search")
        compose.onNode(hasText(longTag) and hasAnyAncestor(hasTestTag("editor-tags-options"))).performClick()
        compose.onNodeWithText(context.getString(R.string.action_confirm)).performClick()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("editor-priority-slider"))
        compose.onNodeWithTag("editor-priority-slider").performScrollTo()
        compose.onNode(hasText("Project collection 9") and hasAnyAncestor(hasTestTag("editor-tags-preview"))).performScrollTo().assertIsSelected()
        compose.onNodeWithTag("editor-more-tags").assertDoesNotExist()
        compose.runOnIdle { assertFalse(longTag in model.uiState.value.tags); assertEquals(16, model.uiState.value.tags.size) }
    }

    @Test fun clockAndKeyboardShareDraftUntilCombinedConfirmation() = exerciseClock(false)

    @Test fun frenchClockKeepsLargeTextAndActionsReadable() = exerciseClock(true)

    private fun exerciseClock(french: Boolean) {
        val resources = context.createConfigurationContext(android.content.res.Configuration(context.resources.configuration).apply {
            setLocale(if (french) java.util.Locale.FRENCH else java.util.Locale.ENGLISH)
        }).resources
        val date = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        model.setSchedule(date, 14 * 60 + 30)
        show(frenchDark = french, realKeyboard = true)
        compose.onNodeWithTag("editor-date-time").performScrollTo().performClick()
        // Allow Material date input’s deferred autofocus to finish before switching modes.
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        showTimeInput()
        compose.onNodeWithTag("editor-hour").performScrollTo().performTextReplacement("25")
        compose.onNodeWithTag("editor-time-mode").assertIsNotEnabled()
        showTimeInput()
        compose.onNodeWithTag("editor-hour").performTextReplacement("16")
        compose.onNodeWithTag("editor-minute").performTextReplacement("45")
        compose.onNodeWithTag("editor-hour").assertTextContains("16")
        compose.onNodeWithTag("editor-minute").assertTextContains("45")
        compose.onNodeWithTag("editor-time-mode").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("editor-time-dial").performScrollTo().assertIsDisplayed()
        capture(if (french) "clock-fr-dark-large" else "clock")
        compose.onNode(hasContentDescription("15", substring = true) and hasAnyAncestor(hasTestTag("editor-time-dial"))).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        compose.onNode((hasText("45") or hasContentDescription("45", substring = true)) and
            hasAnyAncestor(hasTestTag("editor-time-dial"))).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        compose.onNode(hasContentDescription("20", substring = true) and hasAnyAncestor(hasTestTag("editor-time-dial"))).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("editor-time-mode").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        showTimeInput()
        compose.onNodeWithTag("editor-hour").assertTextContains("15")
        compose.onNodeWithTag("editor-minute").assertTextContains("20")
        compose.runOnIdle { assertEquals(870, model.uiState.value.dueTimeMinutes) }
        compose.onNodeWithText(resources.getString(R.string.action_cancel)).performClick()
        compose.runOnIdle { assertEquals(870, model.uiState.value.dueTimeMinutes) }
        compose.onNodeWithTag("editor-date-time").performScrollTo().performClick()
        // Allow Material date input’s deferred autofocus to finish before switching modes.
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.onNodeWithTag("editor-time-dial").performScrollTo()
        compose.onNode(hasContentDescription("15", substring = true) and hasAnyAncestor(hasTestTag("editor-time-dial"))).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        compose.onNode((hasText("30") or hasContentDescription("30", substring = true)) and
            hasAnyAncestor(hasTestTag("editor-time-dial"))).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        compose.onNode(hasContentDescription("20", substring = true) and hasAnyAncestor(hasTestTag("editor-time-dial"))).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        compose.onNodeWithText(resources.getString(R.string.action_confirm)).performClick()
        compose.runOnIdle { assertEquals(920, model.uiState.value.dueTimeMinutes) }
    }

    @Test fun pickerDefaultsPersistAndNextWeekUsesConfiguredBoundary() {
        runBlocking {
            assertTrue(settings.schedulingPreferencesFlow.first().timePickerDial)
            settings.setTimePickerDial(false)
            settings.setDatePickerCalendar(false)
            settings.setFirstDayOfWeek(1)
            assertEquals(SchedulingPreferences(false, false, 1), settings.schedulingPreferencesFlow.first())
            assertEquals(java.util.Calendar.MONDAY, java.util.Calendar.getInstance(
                SchedulingPreferences(firstDayOfWeek = 1).calendarLocale(java.util.Locale.US)).firstDayOfWeek)
            assertEquals(java.util.Calendar.SUNDAY, java.util.Calendar.getInstance(
                SchedulingPreferences(firstDayOfWeek = 7).calendarLocale(java.util.Locale.FRANCE)).firstDayOfWeek)
        }
        show()
        compose.waitUntil(5_000) { model.uiState.value.schedulingPreferences.firstDayOfWeek == 1 }
        compose.onNodeWithText(context.getString(R.string.time_next_week)).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(LocalDate.now().with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.MONDAY)),
                java.time.Instant.ofEpochMilli(model.uiState.value.dueDateMillis!!).atZone(ZoneId.systemDefault()).toLocalDate())
        }
        compose.onNodeWithTag("editor-date-time").performScrollTo().performClick()
        compose.onNodeWithTag("editor-hour").assertExists()
        compose.onNodeWithTag("editor-time-dial").assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.editor_pick_date)).assertExists()
        capture("picker-keyboard-preferences")
        compose.onNodeWithContentDescription(context.getString(R.string.editor_pick_date)).performClick()
        capture("calendar-week-start")
    }

    @Test fun settingsExposePersistentPickerAndWeekChoices() {
        val french = InstrumentationRegistry.getArguments().getString("testLocale") == "fr"
        val settingsContext = context.createConfigurationContext(android.content.res.Configuration(context.resources.configuration).apply {
            setLocale(if (french) java.util.Locale.FRENCH else java.util.Locale.ENGLISH)
        })
        val viewModel = cn.super12138.todo.ui.pages.settings.SettingsInterfaceInteractionViewModel(settings)
        store.put("settings", viewModel)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides settingsContext, LocalResources provides settingsContext.resources,
                LocalConfiguration provides settingsContext.resources.configuration,
                LocalDensity provides Density(LocalDensity.current.density, if (french) 1.3f else 1f)) {
                VerveDoTheme(darkTheme = french, dynamicColor = false, animate = false) {
                    cn.super12138.todo.ui.pages.settings.SettingsInterface(onNavigateUp = {}, viewModel = viewModel)
                }
            }
        }
        fun choose(title: Int, option: String) {
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(settingsContext.getString(title)))
            compose.onNodeWithText(settingsContext.getString(title)).performClick()
            compose.onNode(hasText(option) and isSelectable()).performScrollTo().performClick()
        }
        choose(R.string.scheduling_time_picker, settingsContext.getString(R.string.scheduling_keyboard))
        compose.waitUntil(5_000) { !viewModel.schedulingPreferences.value.timePickerDial }
        choose(R.string.scheduling_date_picker, settingsContext.getString(R.string.scheduling_keyboard))
        compose.waitUntil(5_000) { !viewModel.schedulingPreferences.value.datePickerCalendar }
        choose(R.string.scheduling_week_start, java.time.DayOfWeek.MONDAY.getDisplayName(
            java.time.format.TextStyle.FULL_STANDALONE, settingsContext.resources.configuration.locales[0]))
        compose.waitUntil(5_000) { viewModel.schedulingPreferences.value.firstDayOfWeek == 1 }
        runBlocking { assertEquals(SchedulingPreferences(false, false, 1), settings.schedulingPreferencesFlow.first()) }
        capture("scheduling-settings")
    }

    private fun showTimeInput() {
        if (compose.onAllNodesWithTag("editor-hour").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("editor-time-mode").performScrollTo()
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        }
    }

    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureEditing") != "true") return
        compose.waitForIdle(); instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(300)
        val prefix = InstrumentationRegistry.getArguments().getString("capturePrefix") ?: "phone"
        val file = File(context.getExternalFilesDir(null), "editing-review/$prefix-$name.png")
        file.parentFile!!.mkdirs()
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
