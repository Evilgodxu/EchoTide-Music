package com.yichao.evilgodxu.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yichao.evilgodxu.data.repository.SettingsRepository
import com.yichao.evilgodxu.data.settings.AppLanguage
import com.yichao.evilgodxu.data.settings.ThemeMode
import com.yichao.evilgodxu.data.settings.UsbOutputKind
import com.yichao.evilgodxu.data.settings.usbOutputSwitchesAfter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 设置页状态：能力经环境注入，可单测 */
class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val environment: SettingsEnvironment,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SettingsUiState(version = environment.appVersion),
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _uiState.update { it.copy(themeMode = settings.themeMode) }
            }
        }
        viewModelScope.launch {
            _uiState.update { it.copy(language = settingsRepository.getAppLanguage()) }
        }
        viewModelScope.launch {
            settingsRepository.playbackSettings.collect { playback ->
                _uiState.update { it.copy(playback = playback) }
            }
        }
        // 音源列表由存储状态流驱动
        viewModelScope.launch {
            environment.proxySources.collect { sources ->
                _uiState.update { it.copy(proxySources = sources) }
            }
        }
        refreshCacheUsage()
    }

    fun setMiniPlayerEnabled(enabled: Boolean) {
        _uiState.update { it.copy(playback = it.playback.copy(miniPlayerEnabled = enabled)) }
        viewModelScope.launch {
            settingsRepository.updatePlaybackSettings { it.copy(miniPlayerEnabled = enabled) }
            if (enabled && !environment.canDrawOverlays()) {
                // 置待授权状态，返回时统一对账
                _uiState.update { it.copy(overlayPermissionPending = true) }
                environment.startOverlayGrantMonitor()
            } else if (!enabled) {
                // 关闭开关即停止监控
                environment.stopOverlayGrantMonitor()
                _uiState.update { it.copy(overlayPermissionPending = false) }
            }
        }
    }

    // 免打扰返回：只收尾，不回滚开关
    fun onNotificationPolicySettingsReturned() {
        _uiState.update { it.copy(notificationPolicyPermissionPending = false) }
    }

    // 悬浮窗设置页返回
    fun onOverlaySettingsReturned() {
        _uiState.update { it.copy(overlayPermissionPending = false) }
        if (!environment.canDrawOverlays()) {
            // 未授予则停止监控并回滚开关
            environment.stopOverlayGrantMonitor()
            viewModelScope.launch {
                settingsRepository.updatePlaybackSettings { it.copy(miniPlayerEnabled = false) }
                _uiState.update { it.copy(playback = it.playback.copy(miniPlayerEnabled = false)) }
            }
        }
    }

    fun setWordByWordRendering(enabled: Boolean) {
        _uiState.update { it.copy(playback = it.playback.copy(wordByWordRendering = enabled)) }
        viewModelScope.launch {
            settingsRepository.updatePlaybackSettings { it.copy(wordByWordRendering = enabled) }
        }
    }

    fun setSwipeToChangeTrack(enabled: Boolean) {
        _uiState.update { it.copy(playback = it.playback.copy(swipeToChangeTrack = enabled)) }
        viewModelScope.launch {
            settingsRepository.updatePlaybackSettings { it.copy(swipeToChangeTrack = enabled) }
        }
    }

    fun setBackgroundFlow(enabled: Boolean) {
        _uiState.update { it.copy(playback = it.playback.copy(backgroundFlow = enabled)) }
        viewModelScope.launch {
            settingsRepository.updatePlaybackSettings { it.copy(backgroundFlow = enabled) }
        }
    }

    // 只落盘，由播放服务订阅生效
    fun setVolumeGain(enabled: Boolean) {
        _uiState.update { it.copy(playback = it.playback.copy(volumeGain = enabled)) }
        viewModelScope.launch {
            settingsRepository.updatePlaybackSettings { it.copy(volumeGain = enabled) }
        }
    }

    fun setUsbDirectOutput(enabled: Boolean) {
        applyUsbOutputSwitch(UsbOutputKind.FORMAT_LOCKED, enabled)
        if (enabled) {
            // 直出需免打扰权限，此处申请
            requestNotificationPolicyAccess()
        }
        viewModelScope.launch {
            settingsRepository.setUsbDirectOutput(enabled)
        }
    }

    fun setUsbExclusiveOutput(enabled: Boolean) {
        applyUsbOutputSwitch(UsbOutputKind.EXCLUSIVE, enabled)
        viewModelScope.launch {
            settingsRepository.setUsbExclusiveOutput(enabled)
        }
    }

    // 已授予则跳过，否则置位待界面打开
    private fun requestNotificationPolicyAccess() {
        if (environment.isNotificationPolicyGranted()) return
        environment.watchNotificationPolicyGrant()
        _uiState.update { it.copy(notificationPolicyPermissionPending = true) }
    }

    /** 界面先行更新，落盘异步；互斥判定与落盘共用同一份 */
    private fun applyUsbOutputSwitch(kind: UsbOutputKind, enabled: Boolean) {
        val switches = usbOutputSwitchesAfter(kind, enabled)
        _uiState.update { state ->
            state.copy(
                playback = state.playback.copy(
                    usbDirectOutput = switches[UsbOutputKind.FORMAT_LOCKED] ?: state.playback.usbDirectOutput,
                    usbExclusiveOutput = switches[UsbOutputKind.EXCLUSIVE] ?: state.playback.usbExclusiveOutput,
                ),
            )
        }
    }

    // 层级只记去向
    fun openPage(page: SettingsPage) {
        _uiState.update { it.copy(page = page) }
    }

    fun backToRoot() {
        _uiState.update { it.copy(page = SettingsPage.ROOT) }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            settingsRepository.saveThemeMode(mode)
        }
    }

    fun setLanguage(language: AppLanguage) {
        _uiState.update { it.copy(language = language) }
        environment.applyLanguage(language)
        viewModelScope.launch {
            settingsRepository.setAppLanguage(language)
        }
    }

    // 链接先拉取，文本直接解析
    fun importProxySource(content: String) {
        viewModelScope.launch {
            val message = environment.importProxySource(content)
            _uiState.update {
                it.copy(proxyImportMessage = message.text, proxyImportFailed = message.failed)
            }
            // 列表由存储变更监听刷新
        }
    }

    // 停用即停止参与解析
    fun setProxySourceEnabled(name: String, enabled: Boolean) {
        viewModelScope.launch { environment.setProxySourceEnabled(name, enabled) }
    }

    fun removeProxySource(name: String) {
        viewModelScope.launch { environment.removeProxySource(name) }
    }

    fun clearProxyImportMessage() {
        _uiState.update { it.copy(proxyImportMessage = null, proxyImportFailed = false) }
    }

    // 采样缓存合计，明细在缓存页
    private fun refreshCacheUsage() {
        viewModelScope.launch {
            val totalBytes = environment.sampleCacheBytes()
            _uiState.update { it.copy(cacheTotalBytes = totalBytes) }
        }
    }
}
