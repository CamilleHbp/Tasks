package cn.super12138.todo.logic

import cn.super12138.todo.logic.datastore.DataStoreManager
import cn.super12138.todo.logic.model.ContrastLevel
import cn.super12138.todo.logic.model.DarkMode
import cn.super12138.todo.logic.model.PaletteStyle
import cn.super12138.todo.logic.model.SortingMethod
import kotlinx.coroutines.flow.map

class SettingsRepository(private val dataStoreManager: DataStoreManager, private val syncedTags: cn.super12138.todo.logic.sync.SyncedTags? = null) {
    val appearanceFlow = dataStoreManager.appearanceFlow
    suspend fun setThemeColor(color: Int) = dataStoreManager.setThemeColor(color)
    suspend fun setWidgetTransparency(percent: Int) = dataStoreManager.setWidgetTransparency(percent)
    val dynamicColorFlow = dataStoreManager.dynamicColorFlow
    val paletteStyleFlow = dataStoreManager.paletteStyleFlow.map { PaletteStyle.fromId(it) }
    val darkModeFlow = dataStoreManager.darkModeFlow.map { DarkMode.fromId(it) }
    val pureBlackFlow = dataStoreManager.pureBlackFlow
    val contrastLevelFlow = dataStoreManager.contrastLevelFlow.map { ContrastLevel.fromFloat(it) }
    val previewColorSystemFlow = dataStoreManager.previewColorSystemFlow
    val sortingMethodFlow = dataStoreManager.sortingMethodFlow.map { SortingMethod.fromId(it) }
    val textFieldAutoFocusFlow = dataStoreManager.textFieldAutoFocusFlow
    val secureModeFlow = dataStoreManager.secureModeFlow
    val hapticFeedbackFlow = dataStoreManager.hapticFeedbackFlow
    val categoriesFlow = syncedTags?.names ?: dataStoreManager.categoriesFlow
    val tagIconsFlow = syncedTags?.icons ?: dataStoreManager.tagIconsFlow
    val tagColorsFlow = syncedTags?.colors ?: dataStoreManager.tagColorsFlow
    val tagOrderFlow = dataStoreManager.tagOrderFlow
    suspend fun setTagOrder(order: List<String>) = dataStoreManager.setTagOrder(order)
    val favoriteTagsFlow = dataStoreManager.favoriteTagsFlow
    val tagCloudHintDismissedFlow = dataStoreManager.tagCloudHintDismissedFlow
    suspend fun dismissTagCloudHint() = dataStoreManager.dismissTagCloudHint()
    suspend fun setTagFavorite(tag: String, favorite: Boolean) = dataStoreManager.setTagFavorite(tag, favorite)
    suspend fun reorderFavoriteTags(order: List<String>) = dataStoreManager.reorderFavoriteTags(order)
    val schedulingPreferencesFlow = dataStoreManager.schedulingPreferencesFlow
    suspend fun setTimePickerDial(value: Boolean) = dataStoreManager.setTimePickerDial(value)
    suspend fun setDatePickerCalendar(value: Boolean) = dataStoreManager.setDatePickerCalendar(value)
    suspend fun setFirstDayOfWeek(value: Int) = dataStoreManager.setFirstDayOfWeek(value)
    val rolloverDefaultFlow = dataStoreManager.rolloverDefaultFlow
    suspend fun setRolloverDefault(value: Boolean) = dataStoreManager.setRolloverDefault(value)
    val defaultDueTimeFlow = dataStoreManager.defaultDueTimeFlow
    suspend fun ensureTagColors(tags: List<String>) = syncedTags?.ensure(tags) ?: dataStoreManager.ensureTagColors(tags)
    suspend fun setTagColor(tag: String, color: Int) = syncedTags?.edit(tag, tag, color) ?: dataStoreManager.setTagColor(tag, color)

    suspend fun updateTag(old: String?, replacement: String?, color: Int?, icon: String? = null) =
        syncedTags?.edit(old, replacement, color, icon) ?: dataStoreManager.updateTag(old, replacement, color, icon)
    suspend fun setDefaultDueTime(minutes: Int) = dataStoreManager.setDefaultDueTime(minutes)

    suspend fun setDynamicColor(value: Boolean) = dataStoreManager.setDynamicColor(value)
    suspend fun setPaletteStyle(value: Int) = dataStoreManager.setPaletteStyle(value)
    suspend fun setDarkMode(value: Int) = dataStoreManager.setDarkMode(value)
    suspend fun setPureBlackMode(value: Boolean) = dataStoreManager.setPureBlackMode(value)
    suspend fun setContrastLevel(value: Float) = dataStoreManager.setContrastLevel(value)
    suspend fun setPreviewColorSystem(value: Boolean) =
        dataStoreManager.setPreviewColorSystem(value)

    suspend fun setSortingMethod(value: Int) = dataStoreManager.setSortingMethod(value)
    suspend fun setTextFieldAutoFocus(value: Boolean) =
        dataStoreManager.setTextFieldAutoFocus(value)

    suspend fun setSecureMode(value: Boolean) = dataStoreManager.setSecureMode(value)
    suspend fun setHapticFeedback(value: Boolean) = dataStoreManager.setHapticFeedback(value)
    suspend fun setCategories(value: List<String>) = syncedTags?.ensure(value) ?: dataStoreManager.setCategories(value)
}
