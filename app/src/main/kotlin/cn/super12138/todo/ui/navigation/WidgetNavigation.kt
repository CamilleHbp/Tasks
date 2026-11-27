package cn.super12138.todo.ui.navigation

import androidx.navigation3.runtime.NavKey
import cn.super12138.todo.logic.model.TaskViewConfig
import cn.super12138.todo.logic.model.TaskViewMode

fun TopLevelBackStack<NavKey>.openWidgetCollection(configuration: TaskViewConfig) {
    resetTopLevel(if (configuration.mode == TaskViewMode.Agenda) VerveDoScreen.Overview else VerveDoScreen.Tasks)
    add(VerveDoScreen.TaskCollection(configuration.encode()))
}
