package cn.super12138.todo.ui.widget

import android.Manifest
import android.annotation.SuppressLint
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import cn.super12138.todo.R
import cn.super12138.todo.logic.TaskRepository
import cn.super12138.todo.logic.database.TaskDatabase
import cn.super12138.todo.logic.database.TaskEntity
import cn.super12138.todo.logic.model.TaskViewConfig
import cn.super12138.todo.logic.model.TaskViewMode
import cn.super12138.todo.ui.widget.upcoming.UpcomingTaskWidget
import cn.super12138.todo.ui.widget.upcoming.UpcomingTaskWidgetReceiver
import cn.super12138.todo.ui.widget.upcoming.UpcomingWidgetPreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Exercises actual hosted RemoteViews and PendingIntents, including a stale release registry. */
@SuppressLint("RestrictedApi")
class ExistingWidgetRefreshTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val repository get() = GlobalContext.get().get<TaskRepository>()
    private val dao get() = GlobalContext.get().get<TaskDatabase>().taskDao()
    private val nativeManager = AppWidgetManager.getInstance(context)
    private val glanceManager = GlanceAppWidgetManager(context)
    private val tag = "Widget recovery ${UUID.randomUUID()}"
    private val host = AppWidgetHost(context, 0x56574454)
    private lateinit var scenario: ActivityScenario<ComponentActivity>
    private lateinit var hostView: AppWidgetHostView
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private val glanceId get() = glanceManager.getGlanceIdBy(widgetId)
    private var originalRegistry: Preferences? = null

    // Simulate names persisted by an older R8 release without modifying the widget's settings.
    @Suppress("UNCHECKED_CAST")
    private val registry: DataStore<Preferences> by lazy {
        GlanceAppWidgetManager::class.java.getDeclaredMethod("getOrCreateDataStore")
            .apply { isAccessible = true }.invoke(glanceManager) as DataStore<Preferences>
    }

    @Before fun bindExistingWidget() = runBlocking {
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.BIND_APPWIDGET)
        try {
            widgetId = host.allocateAppWidgetId()
            assertTrue(nativeManager.bindAppWidgetIdIfAllowed(widgetId,
                ComponentName(context, UpcomingTaskWidgetReceiver::class.java), Bundle().apply {
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 330)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 330)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 500)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 500)
                }))
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
        updateAppWidgetState(context, glanceId) {
            UpcomingWidgetPreferences.setCategories(it, setOf(tag))
        }
        scenario.onActivity { activity ->
            host.startListening()
            hostView = host.createView(activity, widgetId, nativeManager.getAppWidgetInfo(widgetId))
            activity.setContentView(hostView)
        }
        repository.insertTask(task("Existing task"))
        awaitText("Existing task")
        originalRegistry = registry.data.first()
    }

    @After fun cleanUp() = runBlocking {
        host.stopListening()
        host.deleteHost()
        if (::scenario.isInitialized) scenario.close()
        originalRegistry?.let { original -> registry.updateData { original } }
        val ids = repository.getAllTasks().first().filter { tag in it.tags }.map { it.id }.toSet()
        if (ids.isNotEmpty()) repository.deleteTaskFromIds(ids)
    }

    @Test fun categoryIconsRenderInHostedWidget() = runBlocking {
        val tags = GlobalContext.get().get<cn.super12138.todo.logic.TagRepository>()
        tags.save(tag, tag, 0xffb15b35.toInt(), "house")
        tags.save("Cinema".takeIf { it in tags.tags.first() }, "Cinema", 0xff945eb5.toInt(), "cinema")
        tags.save("Work".takeIf { it in tags.tags.first() }, "Work", 0xff3877b8.toInt(), "work")
        val existing = repository.getAllTasks().first().single { tag in it.tags }
        repository.updateTask(existing.copy(content = "Review the kitchen drawings", tags = listOf(tag, "Cinema", "Work")))
        UpcomingTaskWidget().update(context, glanceId)
        awaitText("Review the kitchen drawings")
        awaitText("+1")
        delay(500)
        val folder = java.io.File(context.getExternalFilesDir(null), "category-icons-review").apply { mkdirs() }
        java.io.File(folder, "widget.png").outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        click { it.contentDescription == context.getString(R.string.widget_complete_task, "Review the kitchen drawings") }
        withTimeout(10_000) { repository.observeTask(existing.id).first { it?.isCompleted == true } }
        Unit
    }

    @Test fun savedTasksRefreshAnExistingWidgetWithOldClassNamesAndButtonsStillWork() = runBlocking {
        makeRegistryStale()
        repository.insertTask(task("Saved after update"))
        awaitText("Saved after update")
        val saved = repository.getAllTasks().first().single { it.content == "Saved after update" && tag in it.tags }

        click { it.contentDescription == context.getString(R.string.widget_complete_task, saved.content) }
        withTimeout(10_000) { repository.observeTask(saved.id).first { it?.isCompleted == true } }
        awaitText(context.getString(R.string.widget_undo))
        click { it is TextView && it.text.toString() == context.getString(R.string.widget_undo) }
        withTimeout(10_000) { repository.observeTask(saved.id).first { it?.isCompleted == false } }
        assertSameWidgetAndFilter()
    }

    @Test fun packageReplacementRefreshesTheSameWidgetAndPreservesItsFilter() = runBlocking {
        makeRegistryStale()
        val existing = dao.getTasksForTagUpdate().single { tag in it.tags }
        // A direct write keeps this test dependent on upgrade recovery, not the save callback.
        dao.update(existing.copy(content = "Refreshed after APK update"))
        TaskWidgetUpdateReceiver().onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        awaitText("Refreshed after APK update")
        assertSameWidgetAndFilter()
    }

    @Test fun agendaGroupCanBeFoldedAgainAfterUnfolding() = runBlocking {
        val key = "day:${LocalDate.now()}"
        val title = context.getString(R.string.time_today)
        updateAppWidgetState(context, glanceId) {
            it[UpcomingWidgetPreferences.groupsKey] = setOf(key)
        }
        UpcomingTaskWidget().update(context, glanceId)
        awaitHeader(title, collapsed = true)
        // Exercise the title, the arrow, and the middle of the real touch target.
        for (fraction in listOf(0.15f, 0.95f, 0.5f)) {
            tapHeader(title, collapsed = true, fraction)
            awaitGroupState(key, collapsed = false)
            awaitHeader(title, collapsed = false)
            tapHeader(title, collapsed = false, fraction)
            awaitGroupState(key, collapsed = true)
            awaitHeader(title, collapsed = true)
        }
    }

    @Test fun tagGroupsCanBeFoldedAfterTheirRowsMove() = runBlocking {
        val secondTag = "$tag second"
        val existing = repository.getAllTasks().first().single { tag in it.tags }
        repository.updateTask(existing.copy(tags = listOf(tag, secondTag)))
        updateAppWidgetState(context, glanceId) {
            UpcomingWidgetPreferences.setConfiguration(it, TaskViewConfig(
                mode = TaskViewMode.Tags, tags = setOf(tag, secondTag),
                tagOrder = listOf(tag, secondTag), collapsed = setOf("tag:$tag", "tag:$secondTag")))
        }
        UpcomingTaskWidget().update(context, glanceId)
        awaitHeader(tag, collapsed = true)
        awaitHeader(secondTag, collapsed = true)
        repeat(3) {
            for (title in listOf(tag, secondTag)) {
                tapHeader(title, collapsed = true, fraction = 0.95f)
                awaitGroupState("tag:$title", collapsed = false)
                awaitHeader(title, collapsed = false)
            }
            for (title in listOf(tag, secondTag)) {
                tapHeader(title, collapsed = false, fraction = 0.95f)
                awaitGroupState("tag:$title", collapsed = true)
                awaitHeader(title, collapsed = true)
            }
        }
    }

    @Test fun compactAgendaGroupsCanBeFoldedAfterTheirRowsMove() = runBlocking {
        val today = LocalDate.now()
        val titles = listOf(context.getString(R.string.time_today), context.getString(R.string.time_tomorrow))
        val keys = listOf("day:$today", "day:${today.plusDays(1)}")
        repository.insertTask(task("Tomorrow task").copy(dueDateMillis = today.plusDays(1)
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()))
        updateAppWidgetState(context, glanceId) {
            UpcomingWidgetPreferences.setConfiguration(it, TaskViewConfig(
                tags = setOf(tag), collapsed = keys.toSet()))
        }
        scenario.onActivity { activity ->
            (hostView.parent as ViewGroup).removeView(hostView)
            activity.setContentView(FrameLayout(activity).apply {
                val density = activity.resources.displayMetrics.density
                addView(hostView, FrameLayout.LayoutParams((330 * density).toInt(), (210 * density).toInt()))
            })
            hostView.updateAppWidgetSize(null, 330, 210, 330, 210)
        }
        UpcomingTaskWidget().update(context, glanceId)
        titles.forEach { awaitHeader(it, collapsed = true) }
        for (index in titles.indices) {
            tapHeader(titles[index], collapsed = true, fraction = 0.15f)
            awaitGroupState(keys[index], collapsed = false)
            awaitHeader(titles[index], collapsed = false)
        }
        // Collapse the lower day first, without changing the preceding group's height.
        for (index in titles.indices.reversed()) {
            tapHeader(titles[index], collapsed = false, fraction = 0.95f)
            awaitGroupState(keys[index], collapsed = true)
            awaitHeader(titles[index], collapsed = true)
        }
    }

    private fun headerDescription(title: String, collapsed: Boolean) = context.getString(
        R.string.view_section_state, title, 1,
        context.getString(if (collapsed) R.string.view_collapsed else R.string.view_expanded))

    private suspend fun awaitHeader(title: String, collapsed: Boolean) = withTimeout(10_000) {
        val description = headerDescription(title, collapsed)
        while (true) {
            var found = false
            instrumentation.runOnMainSync {
                found = views(hostView).any { it.contentDescription?.toString() == description }
            }
            if (found) break
            delay(50)
        }
    }

    private suspend fun awaitGroupState(key: String, collapsed: Boolean) = withTimeout(5_000) {
        while (true) {
            val state = getAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId)
            if ((key in UpcomingWidgetPreferences.configuration(state).collapsed) == collapsed) break
            delay(50)
        }
    }

    private fun tapHeader(title: String, collapsed: Boolean, fraction: Float) {
        var x = 0f
        var y = 0f
        instrumentation.runOnMainSync {
            val header = views(hostView).first {
                it.contentDescription?.toString() == headerDescription(title, collapsed)
            }
            val location = IntArray(2)
            header.getLocationOnScreen(location)
            x = location[0] + header.width * fraction
            y = location[1] + header.height / 2f
            android.util.Log.i("GroupToggleTest", "Tap $title collapsed=$collapsed at $x,$y size=${header.width}x${header.height}")
        }
        val down = SystemClock.uptimeMillis()
        MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0).also {
            instrumentation.sendPointerSync(it)
            it.recycle()
        }
        MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0).also {
            instrumentation.sendPointerSync(it)
            it.recycle()
        }
    }

    private suspend fun makeRegistryStale() {
        // End the live composition so Room observation cannot hide a missing refresh request.
        UpcomingTaskWidget().getSessionManager(context).runWithLock {
            closeSession("appWidget-$widgetId")
        }
        registry.updateData { current ->
            current.toMutablePreferences().apply {
                this[stringSetPreferencesKey("list::Providers")] = taskWidgetProviders.keys.map { it.name }.toSet()
                taskWidgetProviders.keys.forEach {
                    this[stringPreferencesKey("provider:${it.name}")] = "previous.release.ObfuscatedWidget"
                }
            }
        }
        assertTrue("The old updateAll lookup must miss this existing widget",
            glanceManager.getGlanceIds(UpcomingTaskWidget::class.java).isEmpty())
        assertTrue(nativeManager.getAppWidgetIds(ComponentName(context,
            UpcomingTaskWidgetReceiver::class.java)).contains(widgetId))
    }

    private suspend fun assertSameWidgetAndFilter() {
        assertTrue(nativeManager.getAppWidgetIds(ComponentName(context,
            UpcomingTaskWidgetReceiver::class.java)).contains(widgetId))
        val state = getAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId)
        assertEquals(setOf(tag), UpcomingWidgetPreferences.categories(state))
    }

    private fun task(title: String) = TaskEntity(title, priority = 0f, category = tag,
        tags = listOf(tag), dueDateMillis = LocalDate.now().atStartOfDay(ZoneId.systemDefault())
            .toInstant().toEpochMilli())

    private suspend fun awaitText(text: String) = withTimeout(15_000) {
        while (true) {
            var found = false
            instrumentation.runOnMainSync {
                found = views(hostView).any { it is TextView && it.text.toString() == text }
            }
            if (found) break
            delay(50)
        }
    }

    private fun click(predicate: (View) -> Boolean) {
        instrumentation.runOnMainSync {
            var view = views(hostView).first(predicate)
            while (!view.isClickable && view.parent is View) view = view.parent as View
            assertTrue("Widget action must be clickable", view.performClick())
        }
    }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }
}
