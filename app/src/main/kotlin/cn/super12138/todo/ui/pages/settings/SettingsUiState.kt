package cn.super12138.todo.ui.pages.settings

import cn.super12138.todo.constants.Constants
import cn.super12138.todo.logic.model.SortingMethod

typealias SettingsAppearanceUiState = cn.super12138.todo.logic.model.AppearanceSettings

data class SettingsInterfaceUiState(
    val sortingMethod: SortingMethod = SortingMethod.Sequential,
    val textFieldAutoFocus: Boolean = Constants.PREF_TEXT_FIELD_AUTO_FOCUS_DEFAULT,
    val secureMode: Boolean = Constants.PREF_SECURE_MODE_DEFAULT,
    val hapticFeedback: Boolean = Constants.PREF_HAPTIC_FEEDBACK_DEFAULT,
    val showSortingMethodDialog: Boolean = false
)

data class SettingsDataUiState(
    // val showBackupFormatDialog: Boolean = false,
    val showRestoreDialog: Boolean = false
)

data class SettingsDataCategoryUiState(
    val tagIcons: Map<String, String> = emptyMap(),
    val tagColors: Map<String, Int> = emptyMap(),
    val categories: List<String> = emptyList(),
    val usageCounts: Map<String, Int> = emptyMap(),
    val isSaving: Boolean = false,
    val error: Int? = null
)
