package com.yichao.evilgodxu.data.repository

import com.yichao.evilgodxu.data.settings.AppLanguage
import com.yichao.evilgodxu.data.settings.PlaybackSettings
import com.yichao.evilgodxu.data.settings.SettingsState
import com.yichao.evilgodxu.data.settings.ThemeMode
import com.yichao.evilgodxu.data.settings.UsbOutputKind
import com.yichao.evilgodxu.data.settings.usbOutputSwitchesAfter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

// 内存版设置仓库：单测用它替代真实 DataStore，不经磁盘也不另引 mock 框架。
// USB 互斥复用与实现相同的判定，避免 fake 与真实写入规则各写一遍
class FakeSettingsRepository(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    language: AppLanguage = AppLanguage.SYSTEM,
    playback: PlaybackSettings = PlaybackSettings(),
) : SettingsRepository {

    private val settingsState = MutableStateFlow(SettingsState(themeMode = themeMode))
    private val languageState = MutableStateFlow(language)
    private val playbackState = MutableStateFlow(playback)

    override val settings: Flow<SettingsState> = settingsState

    override val appLanguage: Flow<AppLanguage> = languageState

    override val playbackSettings: Flow<PlaybackSettings> = playbackState

    override suspend fun saveThemeMode(mode: ThemeMode) {
        settingsState.value = SettingsState(themeMode = mode)
    }

    override suspend fun getAppLanguage(): AppLanguage = languageState.value

    override suspend fun setAppLanguage(language: AppLanguage) {
        languageState.value = language
    }

    override suspend fun updatePlaybackSettings(transform: (PlaybackSettings) -> PlaybackSettings) {
        playbackState.value = transform(playbackState.value)
    }

    override suspend fun setUsbDirectOutput(enabled: Boolean) = applyUsbSwitch(UsbOutputKind.FORMAT_LOCKED, enabled)

    override suspend fun setUsbExclusiveOutput(enabled: Boolean) = applyUsbSwitch(UsbOutputKind.EXCLUSIVE, enabled)

    private fun applyUsbSwitch(kind: UsbOutputKind, enabled: Boolean) {
        val switches = usbOutputSwitchesAfter(kind, enabled)
        val current = playbackState.value
        playbackState.value = current.copy(
            usbDirectOutput = switches[UsbOutputKind.FORMAT_LOCKED] ?: current.usbDirectOutput,
            usbExclusiveOutput = switches[UsbOutputKind.EXCLUSIVE] ?: current.usbExclusiveOutput,
        )
    }
}
