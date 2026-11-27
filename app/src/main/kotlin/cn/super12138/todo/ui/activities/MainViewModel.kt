package cn.super12138.todo.ui.activities

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.super12138.todo.logic.SettingsRepository
import cn.super12138.todo.ui.pages.settings.SettingsAppearanceUiState
import cn.super12138.todo.utils.ConfettiController
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class MainViewModel(
    private val settingsRepository: SettingsRepository,
    private val confettiController: ConfettiController
) : ViewModel() {
    val isConfettiVisible = confettiController.visible
    val secureModeFlow = settingsRepository.secureModeFlow
    val hapticFeedbackFlow = settingsRepository.hapticFeedbackFlow
    val previewColorSystemFlow = settingsRepository.previewColorSystemFlow

    val appearanceUiState: StateFlow<SettingsAppearanceUiState> = settingsRepository.appearanceFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SettingsAppearanceUiState()
    )

    fun setConfettiVisibility(visible: Boolean) = confettiController.setVisibility(visible)
}