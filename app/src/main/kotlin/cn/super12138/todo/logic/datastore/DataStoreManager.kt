package cn.super12138.todo.logic.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import cn.super12138.todo.constants.Constants
import cn.super12138.todo.logic.DEFAULT_DUE_TIME_MINUTES
import cn.super12138.todo.logic.assignTagColors
import cn.super12138.todo.logic.model.OverviewLayout
import cn.super12138.todo.logic.model.AppearanceSettings
import cn.super12138.todo.logic.model.DEFAULT_THEME_COLOR
import cn.super12138.todo.logic.model.ContrastLevel
import cn.super12138.todo.logic.model.DarkMode
import cn.super12138.todo.logic.model.PaletteStyle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

class DataStoreManager(val dataStore: DataStore<Preferences>) {
    // Keys
    // 外观与个性化
    private val DYNAMIC_COLOR = booleanPreferencesKey(Constants.PREF_DYNAMIC_COLOR)
    private val PALETTE_STYLE = intPreferencesKey(Constants.PREF_PALETTE_STYLE)
    private val DARK_MODE = intPreferencesKey(Constants.PREF_DARK_MODE)
    private val PURE_BLACK_MODE = booleanPreferencesKey(Constants.PREF_PURE_BLACK_MODE)
    private val CONTRAST_LEVEL = floatPreferencesKey(Constants.PREF_CONTRAST_LEVEL)
    private val PREVIEW_COLOR_SYSTEM = booleanPreferencesKey(Constants.PREF_PREVIEW_COLOR_SYSTEM)
    private val THEME_COLOR = intPreferencesKey("theme_color")
    private val WIDGET_TRANSPARENCY = intPreferencesKey("widget_transparency")

    // Read one snapshot so choosing a custom color and disabling dynamic color is atomic.
    val appearanceFlow = dataStore.data.map { preferences ->
        AppearanceSettings(
            dynamicColor = preferences[DYNAMIC_COLOR] ?: Constants.PREF_DYNAMIC_COLOR_DEFAULT,
            paletteStyle = PaletteStyle.fromId(preferences[PALETTE_STYLE] ?: Constants.PREF_PALETTE_STYLE_DEFAULT),
            darkMode = DarkMode.fromId(preferences[DARK_MODE] ?: Constants.PREF_DARK_MODE_DEFAULT),
            pureBlackMode = preferences[PURE_BLACK_MODE] ?: Constants.PREF_PURE_BLACK_MODE_DEFAULT,
            contrastLevel = ContrastLevel.fromFloat(preferences[CONTRAST_LEVEL] ?: Constants.PREF_CONTRAST_LEVEL_DEFAULT),
            previewColorSystem = preferences[PREVIEW_COLOR_SYSTEM] ?: Constants.PREF_PREVIEW_COLOR_SYSTEM_DEFAULT,
            themeColor = (preferences[THEME_COLOR] ?: DEFAULT_THEME_COLOR) or 0xFF000000.toInt(),
            widgetTransparency = (preferences[WIDGET_TRANSPARENCY] ?: 0).coerceIn(0, 100)
        )
    }

    suspend fun setThemeColor(color: Int) {
        dataStore.edit {
            it[THEME_COLOR] = color or 0xFF000000.toInt()
            it[DYNAMIC_COLOR] = false
        }
    }

    suspend fun setWidgetTransparency(percent: Int) {
        dataStore.edit { it[WIDGET_TRANSPARENCY] = percent.coerceIn(0, 100) }
    }

    // 界面与交互
    // private val SHOW_COMPLETED = booleanPreferencesKey(Constants.PREF_SHOW_COMPLETED)
    private val SORTING_METHOD = intPreferencesKey(Constants.PREF_SORTING_METHOD)
    private val TEXT_FIELD_AUTO_FOCUS = booleanPreferencesKey(Constants.PREF_TEXT_FIELD_AUTO_FOCUS)
    private val SECURE_MODE = booleanPreferencesKey(Constants.PREF_SECURE_MODE)
    private val HAPTIC_FEEDBACK = booleanPreferencesKey(Constants.PREF_HAPTIC_FEEDBACK)

    // 数据
    private val CATEGORIES = stringPreferencesKey(Constants.PREF_CATEGORIES)
    private val TAG_ICONS = stringPreferencesKey("tag_icons")
    val tagIconsFlow = dataStore.data.map { Json.decodeFromString<Map<String, String>>(it[TAG_ICONS] ?: "{}") }
    suspend fun setTagIcons(icons: Map<String, String>) { dataStore.edit { it[TAG_ICONS] = Json.encodeToString(icons) } }
    private val TAG_COLORS = stringPreferencesKey("tag_colors")
    private val FAVORITE_TAGS = stringSetPreferencesKey("favorite_tags")
    private val FAVORITE_TAG_ORDER = stringPreferencesKey("favorite_tag_order")
    private val TAG_CLOUD_HINT_DISMISSED = booleanPreferencesKey("tag_cloud_hint_dismissed")
    private val TIME_PICKER_DIAL = booleanPreferencesKey("time_picker_dial")
    private val DATE_PICKER_CALENDAR = booleanPreferencesKey("date_picker_calendar")
    private val FIRST_DAY_OF_WEEK = intPreferencesKey("first_day_of_week")
    val schedulingPreferencesFlow = dataStore.data.map {
        cn.super12138.todo.logic.SchedulingPreferences(it[TIME_PICKER_DIAL] ?: true,
            it[DATE_PICKER_CALENDAR] ?: true, (it[FIRST_DAY_OF_WEEK] ?: 0).takeIf { day -> day in 0..7 } ?: 0)
    }
    suspend fun setTimePickerDial(value: Boolean) { dataStore.edit { it[TIME_PICKER_DIAL] = value } }
    suspend fun setDatePickerCalendar(value: Boolean) { dataStore.edit { it[DATE_PICKER_CALENDAR] = value } }
    suspend fun setFirstDayOfWeek(value: Int) {
        require(value in 0..7)
        dataStore.edit { it[FIRST_DAY_OF_WEEK] = value }
    }
    private val DEFAULT_DUE_TIME = intPreferencesKey("default_due_time_minutes")
    private val OVERVIEW_LAYOUT = stringPreferencesKey("overview_layout_v1")

    val overviewLayoutFlow = dataStore.data.map { OverviewLayout.decode(it[OVERVIEW_LAYOUT]) }

    suspend fun setOverviewLayout(layout: OverviewLayout) {
        dataStore.edit { it[OVERVIEW_LAYOUT] = layout.normalized().encode() }
    }

    val tagColorsFlow = dataStore.data.map { preferences ->
        Json.decodeFromString<Map<String, Int>>(preferences[TAG_COLORS] ?: "{}")
    }
    private val manualTagOrder = stringPreferencesKey("manual_tag_order")

    val tagOrderFlow = dataStore.data.map { preferences ->
        preferences[manualTagOrder]?.let { Json.decodeFromString<List<String>>(it) }.orEmpty()
    }

    suspend fun setTagOrder(order: List<String>) {
        dataStore.edit { preferences ->
            if (order.isEmpty()) preferences.remove(manualTagOrder)
            else preferences[manualTagOrder] = Json.encodeToString(order)
        }
    }

    private fun favoriteTags(preferences: Preferences): List<String> {
        val favorites = preferences[FAVORITE_TAGS] ?: emptySet()
        val order = runCatching {
            Json.decodeFromString<List<String>>(preferences[FAVORITE_TAG_ORDER] ?: "[]")
        }.getOrDefault(emptyList())
        return (order.filter { it in favorites } + favorites.sortedWith(String.CASE_INSENSITIVE_ORDER)).distinct()
    }

    val favoriteTagsFlow = dataStore.data.map(::favoriteTags)
    val tagCloudHintDismissedFlow = dataStore.data.map { it[TAG_CLOUD_HINT_DISMISSED] ?: false }

    suspend fun dismissTagCloudHint() {
        dataStore.edit { it[TAG_CLOUD_HINT_DISMISSED] = true }
    }

    suspend fun setTagFavorite(tag: String, favorite: Boolean) {
        dataStore.edit { preferences ->
            val current = preferences[FAVORITE_TAGS] ?: emptySet()
            val order = favoriteTags(preferences)
            preferences[FAVORITE_TAGS] = if (favorite) current + tag else current - tag
            preferences[FAVORITE_TAG_ORDER] = Json.encodeToString(if (favorite) (order + tag).distinct() else order - tag)
            if (favorite) {
                val presets = Json.decodeFromString<List<String>>(preferences[CATEGORIES] ?: Constants.PREF_CATEGORIES_DEFAULT)
                preferences[CATEGORIES] = Json.encodeToString((presets + tag).distinct())
            }
        }
    }

    suspend fun reorderFavoriteTags(order: List<String>) {
        dataStore.edit { preferences ->
            val current = favoriteTags(preferences)
            // Preserve tags added concurrently, and never resurrect removed favourites.
            preferences[FAVORITE_TAG_ORDER] = Json.encodeToString(
                (order.filter { it in current } + current).distinct()
            )
        }
    }
    private val rolloverDefaultKey = androidx.datastore.preferences.core.booleanPreferencesKey("rollover_default")
    val rolloverDefaultFlow = dataStore.data.map { it[rolloverDefaultKey] ?: false }
    suspend fun setRolloverDefault(value: Boolean) { dataStore.edit { it[rolloverDefaultKey] = value } }
    val defaultDueTimeFlow = dataStore.data.map { preferences ->
        (preferences[DEFAULT_DUE_TIME] ?: DEFAULT_DUE_TIME_MINUTES).coerceIn(0, 1439)
    }

    suspend fun ensureTagColors(tags: List<String>) {
        dataStore.edit { preferences ->
            val existing = Json.decodeFromString<Map<String, Int>>(preferences[TAG_COLORS] ?: "{}")
            val colors = assignTagColors(tags, existing)
            if (colors != existing) preferences[TAG_COLORS] = Json.encodeToString(colors)
        }
    }

    suspend fun setTagColor(tag: String, color: Int) {
        dataStore.edit { preferences ->
            val existing = Json.decodeFromString<Map<String, Int>>(preferences[TAG_COLORS] ?: "{}")
            preferences[TAG_COLORS] = Json.encodeToString(existing + (tag to color))
        }
    }

    suspend fun updateTag(old: String?, replacement: String?, color: Int?, icon: String? = null) {
        dataStore.edit { preferences ->
            val presets = Json.decodeFromString<List<String>>(preferences[CATEGORIES] ?: Constants.PREF_CATEGORIES_DEFAULT)
            val colors = Json.decodeFromString<Map<String, Int>>(preferences[TAG_COLORS] ?: "{}")
            val updated = if (old == null) presets else cn.super12138.todo.logic.replaceTag(presets, old, replacement)
            preferences[CATEGORIES] = Json.encodeToString((updated + listOfNotNull(replacement)).distinct())
            val icons = Json.decodeFromString<Map<String, String>>(preferences[TAG_ICONS] ?: "{}")
            val newIcons = if (old == null) icons else icons - old
            preferences[TAG_ICONS] = Json.encodeToString(if (replacement != null) newIcons + (replacement to (icon ?: icons[old] ?: "tag")) else newIcons)
            val newColors = if (old == null) colors else colors - old
            preferences[TAG_COLORS] = Json.encodeToString(
                if (replacement != null && color != null) newColors + (replacement to color) else newColors
            )
            if (old != null) {
                preferences[manualTagOrder]?.let { encoded ->
                    val prior = Json.decodeFromString<List<String>>(encoded)
                    preferences[manualTagOrder] = Json.encodeToString(cn.super12138.todo.logic.replaceTag(prior, old, replacement))
                }
                val order = favoriteTags(preferences).mapNotNull { if (it == old) replacement else it }.distinct()
                preferences[FAVORITE_TAGS] = (preferences[FAVORITE_TAGS] ?: emptySet())
                    .mapNotNull { if (it == old) replacement else it }.toSet()
                preferences[FAVORITE_TAG_ORDER] = Json.encodeToString(order)
            }
        }
    }

    suspend fun setDefaultDueTime(minutes: Int) {
        require(minutes in 0..1439)
        dataStore.edit { it[DEFAULT_DUE_TIME] = minutes }
    }

    // Getters
    val dynamicColorFlow: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[DYNAMIC_COLOR] ?: Constants.PREF_DYNAMIC_COLOR_DEFAULT
    }

    val paletteStyleFlow = dataStore.data.map { preferences ->
        preferences[PALETTE_STYLE] ?: Constants.PREF_PALETTE_STYLE_DEFAULT
    }

    val darkModeFlow = dataStore.data.map { preferences ->
        preferences[DARK_MODE] ?: Constants.PREF_DARK_MODE_DEFAULT
    }

    val pureBlackFlow = dataStore.data.map { preferences ->
        preferences[PURE_BLACK_MODE] ?: Constants.PREF_PURE_BLACK_MODE_DEFAULT
    }

    val contrastLevelFlow = dataStore.data.map { preferences ->
        preferences[CONTRAST_LEVEL] ?: Constants.PREF_CONTRAST_LEVEL_DEFAULT
    }

    val previewColorSystemFlow: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[PREVIEW_COLOR_SYSTEM] ?: Constants.PREF_PREVIEW_COLOR_SYSTEM_DEFAULT
    }

    /*val showCompletedFlow: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[SHOW_COMPLETED] ?: Constants.PREF_SHOW_COMPLETED_DEFAULT
    }*/

    val sortingMethodFlow: Flow<Int> = dataStore.data.map { preferences ->
        preferences[SORTING_METHOD] ?: Constants.PREF_SORTING_METHOD_DEFAULT
    }

    val textFieldAutoFocusFlow: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[TEXT_FIELD_AUTO_FOCUS] ?: Constants.PREF_TEXT_FIELD_AUTO_FOCUS_DEFAULT
    }

    val secureModeFlow: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[SECURE_MODE] ?: Constants.PREF_SECURE_MODE_DEFAULT
    }

    val hapticFeedbackFlow: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[HAPTIC_FEEDBACK] ?: Constants.PREF_HAPTIC_FEEDBACK_DEFAULT
    }

    val categoriesFlow: Flow<List<String>> = dataStore.data.map { preferences ->
        Json.decodeFromString(preferences[CATEGORIES] ?: Constants.PREF_CATEGORIES_DEFAULT)
    }

    // Setters
    suspend fun setDynamicColor(value: Boolean) {
        dataStore.edit { preferences ->
            preferences[DYNAMIC_COLOR] = value
        }
    }

    suspend fun setPaletteStyle(value: Int) {
        dataStore.edit { preferences ->
            preferences[PALETTE_STYLE] = value
        }
    }

    suspend fun setDarkMode(value: Int) {
        dataStore.edit { preferences ->
            preferences[DARK_MODE] = value
        }
    }

    suspend fun setPureBlackMode(value: Boolean) {
        dataStore.edit { preferences ->
            preferences[PURE_BLACK_MODE] = value
        }
    }

    suspend fun setContrastLevel(value: Float) {
        dataStore.edit { preferences ->
            preferences[CONTRAST_LEVEL] = value
        }
    }

    suspend fun setPreviewColorSystem(value: Boolean) {
        dataStore.edit { preferences ->
            preferences[PREVIEW_COLOR_SYSTEM] = value
        }
    }

    /*suspend fun setShowCompleted(value: Boolean) {
        dataStore.edit { preferences ->
            preferences[SHOW_COMPLETED] = value
        }
    }*/

    suspend fun setSortingMethod(value: Int) {
        dataStore.edit { preferences ->
            preferences[SORTING_METHOD] = value
        }
    }

    suspend fun setTextFieldAutoFocus(value: Boolean) {
        dataStore.edit { preferences ->
            preferences[TEXT_FIELD_AUTO_FOCUS] = value
        }
    }

    suspend fun setSecureMode(value: Boolean) {
        dataStore.edit { preferences ->
            preferences[SECURE_MODE] = value
        }
    }

    suspend fun setHapticFeedback(value: Boolean) {
        dataStore.edit { preferences ->
            preferences[HAPTIC_FEEDBACK] = value
        }
    }

    suspend fun setCategories(value: List<String>) {
        dataStore.edit { preferences ->
            preferences[CATEGORIES] = Json.encodeToString(value)
        }
    }
}
