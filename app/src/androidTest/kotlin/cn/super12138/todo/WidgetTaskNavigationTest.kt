package cn.super12138.todo

import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.navigation3.runtime.NavKey
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import cn.super12138.todo.logic.TaskRepository
import cn.super12138.todo.logic.database.TaskEntity
import cn.super12138.todo.logic.model.TaskViewConfig
import cn.super12138.todo.logic.model.TaskViewMode
import cn.super12138.todo.logic.model.TaskStatusFilter
import cn.super12138.todo.ui.activities.MainActivity
import cn.super12138.todo.ui.navigation.TopLevelBackStack
import cn.super12138.todo.ui.navigation.VerveDoScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext

class WidgetTaskNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun coldAndWarmWidgetIntentsReturnToTheirOwnCollection() = runBlocking {
        val repository = GlobalContext.get().get<TaskRepository>()
        val title = "Widget navigation ${System.nanoTime()}"
        repository.insertTask(TaskEntity(title, priority = 0f, tags = listOf("Home")))
        val task = repository.getAllTasks().first().single { it.content == title }
        val tags = TaskViewConfig(mode = TaskViewMode.Tags, tags = setOf("Home"), status = TaskStatusFilter.All)
        val agenda = TaskViewConfig(mode = TaskViewMode.Agenda, individualDays = 5, showEmptyDays = true)
        fun intent(config: TaskViewConfig) = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_WIDGET_TASK_ID, task.id)
            putExtra(MainActivity.EXTRA_WIDGET_CONFIGURATION, config.encode())
        }
        try {
            ActivityScenario.launch<MainActivity>(intent(tags)).use { scenario ->
                fun awaitDetail(config: TaskViewConfig) {
                    compose.waitUntil(10_000) {
                        var ready = false
                        scenario.onActivity {
                            val stack = it.scope.get<TopLevelBackStack<NavKey>>()
                            ready = stack.backStack.last() == VerveDoScreen.TaskDetail(task.id) &&
                                stack.backStack.getOrNull(stack.backStack.lastIndex - 1) == VerveDoScreen.TaskCollection(config.encode())
                        }
                        ready
                    }
                    compose.waitForIdle()
                }
                fun assertOrigin(config: TaskViewConfig) {
                    scenario.onActivity {
                        val stack = it.scope.get<TopLevelBackStack<NavKey>>()
                        assertEquals(VerveDoScreen.TaskCollection(config.encode()), stack.backStack[stack.backStack.lastIndex - 1])
                        assertEquals(if (config.mode == TaskViewMode.Agenda) VerveDoScreen.Overview else VerveDoScreen.Tasks, stack.topLevelKey)
                    }
                }
                fun backToCollectionAndRoot(config: TaskViewConfig) {
                    scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                    compose.waitForIdle()
                    scenario.onActivity {
                        assertEquals(VerveDoScreen.TaskCollection(config.encode()), it.scope.get<TopLevelBackStack<NavKey>>().backStack.last())
                        it.onBackPressedDispatcher.onBackPressed()
                    }
                    compose.waitForIdle()
                    scenario.onActivity {
                        assertEquals(if (config.mode == TaskViewMode.Agenda) VerveDoScreen.Overview else VerveDoScreen.Tasks,
                            it.scope.get<TopLevelBackStack<NavKey>>().backStack.last())
                    }
                }
                awaitDetail(tags)
                assertOrigin(tags)
                // Open the same task from an Agenda widget while its Tags detail is still open.
                scenario.onActivity { it.startActivity(intent(agenda)) }
                awaitDetail(agenda)
                assertOrigin(agenda)
                backToCollectionAndRoot(agenda)
                scenario.onActivity { it.startActivity(intent(tags)) }
                awaitDetail(tags)
                scenario.onActivity { it.startActivity(intent(tags)) }
                awaitDetail(tags)
                scenario.recreate()
                awaitDetail(tags)
                assertOrigin(tags)
                backToCollectionAndRoot(tags)
            }
        } finally {
            repository.deleteTask(task)
        }
    }
}
