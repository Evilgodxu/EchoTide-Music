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

// 应用级（Activity 作用域）UI 状态持有者：以 DataStore 为单一事实源聚合主题与语言。
// 写入由设置页经仓库完成，这里只做聚合，不另开写入口
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

// 应用级 UI 状态：聚合全局主题、语言与版本，供主题、本地化与各页面 UI 共同消费。
// 仅被 MainViewModel 占用，随最上层持有者内联于此
data class AppUiState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val language: AppLanguage = AppLanguage.SYSTEM,
    val version: String = "",
)

// 应用级状态的只读下发通道：宿主把 ViewModel 的 StateFlow 收集成值后提供，消费者按值取用。
// 默认值使主题与本地化在没有宿主的场景（预览、单测）下也能独立渲染，无需伪造 ViewModel
val LocalAppUiState = staticCompositionLocalOf { AppUiState() }
