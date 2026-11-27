package cn.super12138.todo.ui.pages.collections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.super12138.todo.logic.SettingsRepository
import cn.super12138.todo.logic.TagRepository
import cn.super12138.todo.logic.TaskRepository
import cn.super12138.todo.logic.database.taskTags
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class TagCloudState(
    val tags: List<String> = emptyList(),
    val colors: Map<String, Int> = emptyMap(),
    val counts: Map<String, Int> = emptyMap(),
    val favorites: List<String> = emptyList(),
    val untaggedCount: Int = 0,
    val showGestureHint: Boolean = false,
    val manualOrder: Boolean = false,
    val loaded: Boolean = false
)

class TagCloudViewModel(
    private val tags: TagRepository,
    tasks: TaskRepository,
    private val settings: SettingsRepository
) : ViewModel() {
    val state = combine(tags.tags, tags.colors, tags.favorites, tasks.getAllTasks(),
        settings.tagCloudHintDismissedFlow) { names, colors, favorites, list, hintDismissed ->
        TagCloudState(
            tags = names,
            colors = colors,
            counts = list.flatMap { it.taskTags }.groupingBy { it }.eachCount(),
            favorites = favorites,
            untaggedCount = list.count { it.taskTags.isEmpty() },
            showGestureHint = !hintDismissed,
            loaded = true
        )
    }.combine(tags.manualOrder) { state, order -> state.copy(manualOrder = order.isNotEmpty()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TagCloudState())

    private val failures = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val errors = failures.asSharedFlow()

    fun dismissGestureHint() {
        viewModelScope.launch {
            runCatching { settings.dismissTagCloudHint() }.onFailure { failures.emit(Unit) }
        }
    }

    fun setFavorite(tag: String, favorite: Boolean) {
        viewModelScope.launch {
            runCatching { tags.setFavorite(tag, favorite) }.onFailure { failures.emit(Unit) }
        }
    }

    fun reorderTags(order: List<String>) {
        viewModelScope.launch {
            runCatching { tags.setOrder(order) }.onFailure { failures.emit(Unit) }
        }
    }
}
