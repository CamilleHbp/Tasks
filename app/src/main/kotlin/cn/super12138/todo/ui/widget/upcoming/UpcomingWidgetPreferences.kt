package cn.super12138.todo.ui.widget.upcoming

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import cn.super12138.todo.R

object UpcomingWidgetPreferences {
    val viewKey = stringPreferencesKey("task_view_v1")
    val groupsKey = stringSetPreferencesKey("task_view_collapsed_groups")

    fun configuration(preferences: Preferences): cn.super12138.todo.logic.model.TaskViewConfig {
        val saved = cn.super12138.todo.logic.model.TaskViewConfig.decode(preferences[viewKey])
        val legacy = collapsedSections(preferences).map { section ->
            when (section) {
                "Today" -> "day:" + java.time.LocalDate.now()
                "Tomorrow" -> "day:" + java.time.LocalDate.now().plusDays(1)
                "ThisWeek" -> "this-week"
                "NextWeek" -> "next-week"
                else -> section.lowercase()
            }
        }.toSet()
        return saved.copy(sort = sort(preferences), tags = categories(preferences),
            controlsVisible = preferences[controlsKey] ?: false,
            collapsed = preferences[groupsKey] ?: legacy)
    }

    fun setConfiguration(preferences: MutablePreferences, config: cn.super12138.todo.logic.model.TaskViewConfig) {
        preferences[viewKey] = config.encode()
        preferences[sortKey] = config.sort.name
        setCategories(preferences, config.tags)
        preferences[controlsKey] = config.controlsVisible
        preferences[groupsKey] = config.collapsed
    }

    val sortKey = stringPreferencesKey("upcoming_sort")
    val categoryKey = stringPreferencesKey("upcoming_category") // Legacy single-tag filter.
    val categoriesKey = stringSetPreferencesKey("upcoming_categories")
    val controlsKey = booleanPreferencesKey("upcoming_controls_visible")
    val collapsedSectionsKey = stringSetPreferencesKey("upcoming_collapsed_sections")
    val panelKey = stringPreferencesKey("upcoming_panel")
    val undoIdKey = intPreferencesKey("upcoming_undo_id")
    val undoUntilKey = longPreferencesKey("upcoming_undo_until")
    val undoTitleKey = stringPreferencesKey("upcoming_undo_title")

    fun undoTaskId(preferences: Preferences, now: Long = System.currentTimeMillis()): Int? =
        preferences[undoIdKey]?.takeIf { now < (preferences[undoUntilKey] ?: 0L) }

    fun clearUndo(preferences: MutablePreferences) {
        preferences.remove(undoIdKey)
        preferences.remove(undoUntilKey)
        preferences.remove(undoTitleKey)
    }

    fun sort(preferences: Preferences): UpcomingTaskSort =
        UpcomingTaskSort.entries.find { it.name == preferences[sortKey] } ?: UpcomingTaskSort.DueDate

    fun collapsedSections(preferences: Preferences): Set<String> = preferences[collapsedSectionsKey].orEmpty()

    fun toggleSection(preferences: MutablePreferences, section: UpcomingTaskSection) {
        val collapsed = collapsedSections(preferences)
        preferences[collapsedSectionsKey] = if (section.name in collapsed) collapsed - section.name
            else collapsed + section.name
    }

    fun categories(preferences: Preferences): Set<String> = preferences[categoriesKey]
        ?: preferences[categoryKey]?.let { setOf(it.ifBlank { "" }) } ?: emptySet()

    fun setCategories(preferences: MutablePreferences, categories: Set<String>) {
        preferences[categoriesKey] = categories
        preferences.remove(categoryKey)
    }

    fun replaceTag(preferences: MutablePreferences, old: String, replacement: String?) {
        val config = configuration(preferences)
        setConfiguration(preferences, config.copy(
            tags = config.tags.mapNotNull { if (it == old) replacement else it }.toSet(),
            tagOrder = config.tagOrder.mapNotNull { if (it == old) replacement else it }.distinct(),
            collapsed = config.collapsed.mapNotNull { if (it == "tag:$old") replacement?.let { name -> "tag:$name" } else it }.toSet()))
        val selected = categories(preferences)
        if (old in selected) setCategories(preferences,
            selected.mapNotNull { if (it == old) replacement else it }.toSet())
    }
}

fun UpcomingTaskSort.labelRes(): Int = when (this) {
    UpcomingTaskSort.DueDate -> R.string.widget_due_soonest
    UpcomingTaskSort.DueDateLatest -> R.string.widget_due_latest
    UpcomingTaskSort.CreatedNewest -> R.string.widget_created_newest
    UpcomingTaskSort.CreatedOldest -> R.string.widget_created_oldest
    UpcomingTaskSort.Priority -> R.string.widget_priority_highest
    UpcomingTaskSort.Alphabetical -> R.string.widget_alphabetical
}
