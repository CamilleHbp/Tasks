package cn.super12138.todo.logic

import androidx.navigation3.runtime.NavKey
import cn.super12138.todo.logic.model.TaskViewConfig
import cn.super12138.todo.logic.model.TaskViewMode
import cn.super12138.todo.logic.model.TaskStatusFilter
import cn.super12138.todo.logic.model.TaskGroupSort
import cn.super12138.todo.ui.navigation.TopLevelBackStack
import cn.super12138.todo.ui.navigation.VerveDoScreen
import cn.super12138.todo.ui.navigation.openWidgetCollection
import org.junit.Assert.*
import org.junit.Test

class TaskNavigationTest {
    @Test fun widgetTaskReturnsToItsModeAndConfigurationFromEitherAppTab() {
        TaskViewMode.entries.forEach { mode ->
            val config = TaskViewConfig(mode = mode, tags = setOf("Home"),
                status = TaskStatusFilter.All, sort = TaskGroupSort.Priority, collapsed = setOf("tag:Work"))
            val stack = TopLevelBackStack<NavKey>(VerveDoScreen.Overview)
            stack.addTopLevel(VerveDoScreen.Tasks)
            stack.add(VerveDoScreen.TaskDetail(99))
            stack.openWidgetCollection(config)
            stack.add(VerveDoScreen.TaskDetail(1))
            stack.removeLast()
            assertEquals(VerveDoScreen.TaskCollection(config.encode()), stack.backStack.last())
            stack.removeLast()
            assertEquals(if (mode == TaskViewMode.Agenda) VerveDoScreen.Overview else VerveDoScreen.Tasks,
                stack.backStack.last())
        }
    }

    @Test fun repeatedWidgetLaunchesReplaceOldTaskAndCollectionHistory() {
        val stack = TopLevelBackStack<NavKey>(VerveDoScreen.Overview)
        val config = TaskViewConfig(mode = TaskViewMode.Tags, tags = setOf("Home"))
        repeat(3) {
            stack.openWidgetCollection(config)
            stack.add(VerveDoScreen.TaskDetail(1))
        }
        stack.removeLast()
        stack.removeLast()
        assertEquals(listOf(VerveDoScreen.Overview, VerveDoScreen.Tasks), stack.backStack.toList())
    }

    @Test fun openingWidgetPreservesHistoryInOtherTabs() {
        val stack = TopLevelBackStack<NavKey>(VerveDoScreen.Overview)
        val agendaTask = VerveDoScreen.TaskDetail(99)
        stack.add(agendaTask)
        stack.openWidgetCollection(TaskViewConfig(mode = TaskViewMode.Tags))
        stack.addTopLevel(VerveDoScreen.Overview)
        assertEquals(agendaTask, stack.backStack.last())
    }

    @Test fun openingAndEditingReturnsToOriginalScreen() {
        val stack = TopLevelBackStack("Agenda")
        stack.addTopLevel("Tags")
        stack.add("Home tasks")
        stack.add("Task detail")
        stack.add("Task editor")
        stack.removeLast()
        assertEquals("Task detail", stack.backStack.last())
        stack.removeLast()
        assertEquals("Home tasks", stack.backStack.last())
        stack.addTopLevel("Agenda")
        stack.add("Widget task detail")
        stack.removeLast()
        assertEquals("Agenda", stack.backStack.last())
    }

    @Test fun tagBrowsingReplacesCollectionAndRootBackIsSafe() {
        val stack = TopLevelBackStack("Agenda")
        stack.removeLast()
        assertEquals(listOf("Agenda"), stack.backStack.toList())
        stack.addTopLevel("Tags")
        stack.add("Home tasks")
        stack.replaceTop("Work tasks")
        stack.removeLast()
        assertEquals("Tags", stack.backStack.last())
    }
}
