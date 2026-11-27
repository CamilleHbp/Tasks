package cn.super12138.todo.logic.sync

import cn.super12138.todo.logic.database.TaskDao
import cn.super12138.todo.logic.datastore.DataStoreManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Room owns shared tag identities; DataStore retains device preferences and a widget cache. */
class SyncedTags(private val dao: TaskDao, private val preferences: DataStoreManager) {
    private val initialization = Mutex()
    suspend fun initialize() = initialization.withLock {
        dao.importTagPreferences(preferences.categoriesFlow.first(), preferences.tagColorsFlow.first())
    }
    private val records = flow { initialize(); emitAll(dao.observeSyncTags()) }
    val names = records.map { values -> values.map { it.name }.sortedWith(String.CASE_INSENSITIVE_ORDER) }.distinctUntilChanged()
    val icons = records.map { values -> values.associate { it.name to it.icon } }.distinctUntilChanged()
    val colors = records.map { values -> values.associate { it.name to it.color } }.distinctUntilChanged()
    suspend fun edit(old: String?, replacement: String?, color: Int?, icon: String? = null) {
        initialize(); dao.editSyncedTag(old, replacement, color, icon)
        preferences.updateTag(old, replacement, color, icon)
    }
    suspend fun ensure(names: List<String>) {
        initialize(); val current = dao.syncTags().filter { !it.deleted }.map { it.name }
        names.filter { it !in current }.forEach { dao.editSyncedTag(null, it, null) }
        refreshWidgetCache()
    }
    suspend fun refreshWidgetCache() {
        val records = dao.syncTags().filter { !it.deleted }
        preferences.setCategories(records.map { it.name })
        records.forEach { preferences.setTagColor(it.name, it.color) }
        preferences.setTagIcons(records.associate { it.name to it.icon })
    }
}
