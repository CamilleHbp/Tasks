package cn.super12138.todo.logic

import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import cn.super12138.todo.logic.database.taskTags

data class TagChange(val old: String, val replacement: String?)
class DuplicateTagException : IllegalArgumentException()

internal fun replaceTag(tags: List<String>, old: String, replacement: String?): List<String> =
    tags.mapNotNull { if (it == old) replacement else it }.distinct()

/** Owns global tag identities across presets, tasks, and editor drafts. */
class TagRepository(
    private val settingsRepository: SettingsRepository,
    private val taskRepository: TaskRepository
) {
    private val mutations = Mutex()
    private val _changes = MutableSharedFlow<TagChange>(extraBufferCapacity = 16)
    val changes = _changes.asSharedFlow()
    val icons = settingsRepository.tagIconsFlow
    val favorites = settingsRepository.favoriteTagsFlow.distinctUntilChanged()

    suspend fun setFavorite(tag: String, favorite: Boolean) = mutations.withLock {
        require(tag in tags.first())
        settingsRepository.setTagFavorite(tag, favorite)
    }

    suspend fun reorderFavorites(order: List<String>) = mutations.withLock {
        settingsRepository.reorderFavoriteTags(order)
    }

    val manualOrder = settingsRepository.tagOrderFlow.distinctUntilChanged()

    suspend fun setOrder(order: List<String>) = mutations.withLock {
        val current = tags.first().toSet()
        settingsRepository.setTagOrder(order.filter { it in current }.distinct())
    }

    val tags = combine(settingsRepository.categoriesFlow, taskRepository.getCategories(), manualOrder) { presets, used, order ->
        val catalog = tagCatalog(presets, used)
        (order.filter { it in catalog } + catalog).distinct()
    }.distinctUntilChanged()

    val usageCounts = taskRepository.getAllTasks().map { tasks ->
        tasks.flatMap { it.taskTags }.groupingBy { it }.eachCount()
    }

    val colors = combine(tags, settingsRepository.tagColorsFlow) { tags, colors ->
        assignTagColors(tags, colors)
    }.distinctUntilChanged().onEach {
        mutations.withLock { settingsRepository.ensureTagColors(tags.first()) }
    }

    suspend fun save(old: String?, input: String, color: Int, icon: String? = null): String = mutations.withLock {
        val name = if (input == old) input else input.trim()
        require(name.isNotBlank())
        if (name != old && tags.first().any { it != old && it.trim().equals(name, ignoreCase = true) }) {
            throw DuplicateTagException()
        }
        settingsRepository.updateTag(old, name, color, icon)
        if (old != null && old != name) taskRepository.replaceTag(old, name)
        if (old != null && old != name) _changes.emit(TagChange(old, name))
        name
    }

    suspend fun delete(tag: String) = mutations.withLock {
        settingsRepository.updateTag(tag, null, null)
        taskRepository.replaceTag(tag, null)
        _changes.emit(TagChange(tag, null))
    }
}

internal fun tagCatalog(presets: List<String>, used: List<String>): List<String> =
    (presets + used).filter { it.isNotBlank() }.distinct()
        .sortedWith(compareBy(java.text.Collator.getInstance().apply { strength = java.text.Collator.SECONDARY }) { it.trim() })

/** Prefer an exact existing identity, then reuse its spelling instead of creating a case variant. */
internal fun resolveTag(input: String, existing: List<String>): String {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return ""
    return existing.firstOrNull { it == input }
        ?: existing.firstOrNull { it == trimmed }
        ?: existing.firstOrNull { it.trim().equals(trimmed, ignoreCase = true) }
        ?: trimmed
}

internal fun matchingTags(input: String, existing: List<String>): List<String> {
    val query = input.trim()
    return existing.filter { it.contains(query, ignoreCase = true) }
        .sortedBy { !it.trim().startsWith(query, ignoreCase = true) }
}
