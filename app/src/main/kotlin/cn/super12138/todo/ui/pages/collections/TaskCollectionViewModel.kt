package cn.super12138.todo.ui.pages.collections

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.super12138.todo.logic.TaskRepository
import cn.super12138.todo.logic.TagRepository
import cn.super12138.todo.logic.TaskViewsRepository
import cn.super12138.todo.logic.database.TaskEntity
import cn.super12138.todo.logic.model.TaskViewConfig
import cn.super12138.todo.logic.model.TaskViewMode
import cn.super12138.todo.utils.updateTaskWidgets
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate

data class TaskCollectionState(
    val tasks: List<TaskEntity> = emptyList(),
    val colors: Map<String, Int> = emptyMap(),
    val config: TaskViewConfig = TaskViewConfig(),
    val today: LocalDate = LocalDate.now(),
    val loaded: Boolean = false
)

class TaskCollectionViewModel(
    private val mode: TaskViewMode,
    initialConfiguration: String?,
    private val tasks: TaskRepository,
    private val tags: TagRepository,
    private val preferences: TaskViewsRepository,
    context: Context,
    private val refreshWidgets: suspend () -> Unit = { updateTaskWidgets(context) }
) : ViewModel() {
    val catalog = tags.tags
    private val standalone = MutableStateFlow(initialConfiguration?.let { TaskViewConfig.decode(it, mode) })
    private val configuration = combine(preferences.observe(mode), standalone) { saved, override -> override ?: saved }
    private val dates = flow {
        while (true) {
            emit(LocalDate.now())
            delay(30_000)
        }
    }.distinctUntilChanged()
    val state = combine(tasks.getAllTasks(), tags.colors, configuration, dates) { list, colors, config, today ->
        TaskCollectionState(list, colors, config, today, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TaskCollectionState(config = TaskViewConfig(mode = mode)))
    private val failures = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val errors = failures.asSharedFlow()

    suspend fun edit(ids: Set<Int>, edit: cn.super12138.todo.logic.BulkTaskEdit): Boolean = runCatching {
        tasks.editTasks(ids, edit)
    }.fold({ true }, { failures.emit(Unit); false })

    fun configure(transform: (TaskViewConfig) -> TaskViewConfig) {
        viewModelScope.launch {
            runCatching {
                if (standalone.value != null) standalone.update { transform(requireNotNull(it)).normalized() }
                else preferences.update(mode, transform)
            }.onFailure { failures.emit(Unit) }
        }
    }

    suspend fun setCompleted(id: Int, completed: Boolean): Boolean = runCatching {
        if (completed) tasks.completeTask(id) else tasks.restoreTask(id)
        runCatching { refreshWidgets() }.onFailure { android.util.Log.w("TaskViews", "Could not refresh widgets", it) }
    }.fold({ true }, { failures.emit(Unit); false })

    suspend fun delete(ids: Set<Int>): Boolean = runCatching {
        tasks.deleteTaskFromIds(ids)
        runCatching { refreshWidgets() }.onFailure { android.util.Log.w("TaskViews", "Could not refresh widgets", it) }
    }.fold({ true }, { failures.emit(Unit); false })
}
