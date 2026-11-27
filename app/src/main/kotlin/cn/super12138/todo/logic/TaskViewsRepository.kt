package cn.super12138.todo.logic

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import cn.super12138.todo.logic.model.TaskStatusFilter
import cn.super12138.todo.logic.model.TaskViewConfig
import cn.super12138.todo.logic.model.TaskViewMode
import kotlinx.coroutines.flow.map

class TaskViewsRepository(private val store: DataStore<Preferences>) {
    private val statusKey = stringPreferencesKey("task_views_status")
    private fun key(mode: TaskViewMode) = stringPreferencesKey("task_view_${mode.name.lowercase()}_v1")

    fun observe(mode: TaskViewMode) = store.data.map { preferences ->
        TaskViewConfig.decode(preferences[key(mode)], mode).copy(mode = mode,
            status = TaskStatusFilter.entries.find { it.name == preferences[statusKey] } ?: TaskStatusFilter.Open)
    }

    suspend fun update(mode: TaskViewMode, transform: (TaskViewConfig) -> TaskViewConfig) {
        store.edit { preferences ->
            val current = TaskViewConfig.decode(preferences[key(mode)], mode).copy(mode = mode,
                status = TaskStatusFilter.entries.find { it.name == preferences[statusKey] } ?: TaskStatusFilter.Open)
            val updated = transform(current).copy(mode = mode).normalized()
            preferences[key(mode)] = updated.encode()
            preferences[statusKey] = updated.status.name
        }
    }
    suspend fun replaceTag(old: String, replacement: String?) {
        store.edit { preferences ->
            TaskViewMode.entries.forEach { mode ->
                val config = TaskViewConfig.decode(preferences[key(mode)], mode)
                preferences[key(mode)] = config.copy(
                    tags = config.tags.mapNotNull { if (it == old) replacement else it }.toSet(),
                    tagOrder = config.tagOrder.mapNotNull { if (it == old) replacement else it }.distinct(),
                    collapsed = config.collapsed.mapNotNull {
                        if (it == "tag:$old") replacement?.let { name -> "tag:$name" } else it
                    }.toSet()).encode()
            }
        }
    }

}
