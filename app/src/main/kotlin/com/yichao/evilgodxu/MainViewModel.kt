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

// 应用级（Activity 作用域）UI 状态持有者：以 DataStore 为单一事实源，聚合主题与语言，
// 供全局主题与本地化共同消费，UI 层不直连数据源
class MainViewModel(
    private val settingsRepository: SettingsRepository,
    appVersion: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppUiState(version = appVersion))
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

    init {
        // 数据流回流更新 UI 状态，写入后经同一持有者广播
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

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsRepository.saveThemeMode(mode) }
    }

    fun setLanguage(language: AppLanguage) {
        viewModelScope.launch { settingsRepository.setAppLanguage(language) }
    }
}

// 应用级 UI 状态：聚合全局主题、语言与版本，供主题、本地化与各页面 UI 共同消费。
// 仅被 MainViewModel 占用，随最上层持有者内联于此
data class AppUiState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val language: AppLanguage = AppLanguage.SYSTEM,
    val version: String = "",
)

// 供界面树消费的 CompositionLocal，由宿主 Activity 提供
val LocalMainViewModel = staticCompositionLocalOf<MainViewModel> {
    error("MainViewModel is not provided")
}