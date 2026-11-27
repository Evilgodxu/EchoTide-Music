package com.yichao.evilgodxu

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yichao.evilgodxu.data.repository.SettingsRepository
import com.yichao.evilgodxu.data.settings.AppLanguage
import com.yichao.evilgodxu.data.settings.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// 应用级状态持有者：只聚合主题与语言，不写回
class MainViewModel(
    private val settingsRepository: SettingsRepository,
    appVersion: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppUiState(version = appVersion))
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _uiState.update { it.copy(themeMode = settings.themeMode) }
            }
        }
        viewModelScope.launch {
            settingsRepository.appLanguage.collect { language ->
                _uiState.update { it.copy(language = language) }
            }
        }
    }
}

// 应用级 UI 状态：主题、语言与版本
data class AppUiState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val language: AppLanguage = AppLanguage.SYSTEM,
    val version: String = "",
)

// 应用级状态下发通道；默认值供预览与单测
val LocalAppUiState = staticCompositionLocalOf { AppUiState() }
