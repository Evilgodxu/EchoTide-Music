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

/**
 * 设置页状态持有者。
 *
 * 不持有 Context：平台能力（代理音源存储、缓存采样、三条授权流程）经 [SettingsEnvironment] 注入，
 * 因此可在 JVM 单测里用假环境替换；状态与判定留在本类，写盘统一走 [SettingsRepository]。
 */
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
        refreshProxySources()
        refreshCacheUsage()
        // 分享导入等外部写入不经过本 ViewModel，注册监听后由存储变化统一驱动列表刷新
        environment.registerProxySourceChanges(::refreshProxySources)
    }

    override fun onCleared() {
        environment.unregisterProxySourceChanges()
    }

    fun setMiniPlayerEnabled(enabled: Boolean) {
        _uiState.update { it.copy(playback = it.playback.copy(miniPlayerEnabled = enabled)) }
        viewModelScope.launch {
            settingsRepository.updatePlaybackSettings { it.copy(miniPlayerEnabled = enabled) }
            if (enabled && !environment.canDrawOverlays()) {
                // 置为待授权状态：界面通过 ActivityResultLauncher 打开系统悬浮窗设置页，
                // 返回时（含 Activity 重建、进程回收后的结果重投递）统一由 onOverlaySettingsReturned 对账
                _uiState.update { it.copy(overlayPermissionPending = true) }
                environment.startOverlayGrantMonitor()
            } else if (!enabled) {
                // 主动关闭开关时结束监控，避免无意义轮询
                environment.stopOverlayGrantMonitor()
                _uiState.update { it.copy(overlayPermissionPending = false) }
            }
        }
    }

    // 用户从免打扰授权页返回（ActivityResultLauncher 回调）：授权与否都只需收尾置位，
    // 直出本身不依赖它，缺权限只是「仅闹钟」这一效果不生效，故不回滚开关
    fun onNotificationPolicySettingsReturned() {
        _uiState.update { it.copy(notificationPolicyPermissionPending = false) }
    }

    // 用户从系统悬浮窗设置页返回（ActivityResultLauncher 回调）
    fun onOverlaySettingsReturned() {
        _uiState.update { it.copy(overlayPermissionPending = false) }
        if (!environment.canDrawOverlays()) {
            // 未授予：停止监控并回滚开关，避免界面状态与真实权限不一致
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

    fun setUsbDirectOutput(enabled: Boolean) {
        applyUsbOutputSwitch(UsbOutputKind.FORMAT_LOCKED, enabled)
        if (enabled) {
            // 面板的设备能力按描述符给（支持采样率、支持编码都取自这里），读描述符要 USB 授权。
            // 开关打开是用户明确表示"要走这台解码器"的时刻，此时要授权有上下文，也不会打断日常听歌
            environment.requestUsbAccess()
            // 直出期间要把系统置为「仅闹钟」：免打扰访问权在这一刻申请。
            // 与悬浮窗授权同一套——授权页由界面在同任务内打开，这里只负责置位与跟随：
            // 该页在用户勾选后不会自己关闭，得靠跟随把应用带回前台
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

    // 申请免打扰访问权：已授予时什么都不做，未授予则置位由界面打开授权页
    private fun requestNotificationPolicyAccess() {
        if (environment.isNotificationPolicyGranted()) return
        environment.watchNotificationPolicyGrant()
        _uiState.update { it.copy(notificationPolicyPermissionPending = true) }
    }

    /**
     * 界面状态先行更新，落盘随后异步进行。
     *
     * 开关的取值与互斥关系都由 [usbOutputSwitchesAfter] 给出，与落盘走的是同一份判定：两处各写一遍
     * 会让界面显示的与文件里存的不是同一件事，而这类分歧只在下次启动才显形。
     */
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

    // 导入代理音源：链接先拉取、文本直接解析；成功不显示提示，失败提示自动消失
    fun importProxySource(content: String) {
        viewModelScope.launch {
            val message = environment.importProxySource(content)
            _uiState.update {
                it.copy(proxyImportMessage = message.text, proxyImportFailed = message.failed)
            }
            // 列表刷新由存储变更监听统一驱动
        }
    }

    // 切换代理音源启用状态，停用的音源即时停止参与解析
    fun setProxySourceEnabled(name: String, enabled: Boolean) {
        viewModelScope.launch { environment.setProxySourceEnabled(name, enabled) }
    }

    fun removeProxySource(name: String) {
        viewModelScope.launch { environment.removeProxySource(name) }
    }

    fun clearProxyImportMessage() {
        _uiState.update { it.copy(proxyImportMessage = null, proxyImportFailed = false) }
    }

    // 采样缓存合计占用：目录遍历为阻塞 IO，由环境侧切到 IO 线程。
    // 明细与清理在缓存页进行，此处只需总量供入口展示
    private fun refreshCacheUsage() {
        viewModelScope.launch {
            val totalBytes = environment.sampleCacheBytes()
            _uiState.update { it.copy(cacheTotalBytes = totalBytes) }
        }
    }

    private fun refreshProxySources() {
        _uiState.update { it.copy(proxySources = environment.proxySources()) }
    }
}
