package com.yichao.evilgodxu.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.yichao.evilgodxu.data.settings.AppLanguage
import com.yichao.evilgodxu.data.settings.PlaybackSettings
import com.yichao.evilgodxu.data.settings.applyPlaybackSettings
import com.yichao.evilgodxu.data.settings.saveUsbDirectOutputMode
import com.yichao.evilgodxu.data.settings.saveUsbExclusiveOutputMode
import com.yichao.evilgodxu.data.settings.settingsDataStore
import com.yichao.evilgodxu.data.settings.SettingsKeys
import com.yichao.evilgodxu.data.settings.SettingsState
import com.yichao.evilgodxu.data.settings.ThemeMode
import com.yichao.evilgodxu.data.settings.toPlaybackSettings
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// 设置仓库契约；ViewModel 只依赖本接口
interface SettingsRepository {

    // 主题模式流
    val settings: Flow<SettingsState>

    // 应用语言流：统一读 DataStore
    val appLanguage: Flow<AppLanguage>

    // 播放设置流
    val playbackSettings: Flow<PlaybackSettings>

    suspend fun saveThemeMode(mode: ThemeMode)

    suspend fun getAppLanguage(): AppLanguage

    suspend fun setAppLanguage(language: AppLanguage)

    // 整组编辑落盘，调用方只改所需字段
    suspend fun updatePlaybackSettings(transform: (PlaybackSettings) -> PlaybackSettings)

    // 两路 USB 输出互斥
    suspend fun setUsbDirectOutput(enabled: Boolean)

    suspend fun setUsbExclusiveOutput(enabled: Boolean)
}

// DataStore 实现，为设置项唯一事实源
class DataStoreSettingsRepository(private val context: Context) : SettingsRepository {

    // edit 会重发整份 Preferences，按派生值去重
    override val settings: Flow<SettingsState> = context.settingsDataStore.data.map { preferences ->
        SettingsState(
            themeMode = ThemeMode.fromValue(preferences[SettingsKeys.THEME_MODE] ?: ThemeMode.SYSTEM.value),
        )
    }.distinctUntilChanged()

    // 按语言值去重，理由同上
    override val appLanguage: Flow<AppLanguage> = context.settingsDataStore.data.map { preferences ->
        AppLanguage.fromTag(preferences[SettingsKeys.LANGUAGE])
    }.distinctUntilChanged()

    // 按快照去重，避免无效刷新
    override val playbackSettings: Flow<PlaybackSettings> = context.settingsDataStore.data.map { preferences ->
        preferences.toPlaybackSettings()
    }.distinctUntilChanged()

    override suspend fun saveThemeMode(mode: ThemeMode) {
        context.settingsDataStore.edit { preferences ->
            preferences[SettingsKeys.THEME_MODE] = mode.value
        }
    }

    override suspend fun getAppLanguage(): AppLanguage {
        return context.settingsDataStore.data.first().let { preferences ->
            AppLanguage.fromTag(preferences[SettingsKeys.LANGUAGE])
        }
    }

    // 语言流驱动 Compose 热切换
    override suspend fun setAppLanguage(language: AppLanguage) {
        context.settingsDataStore.edit { preferences ->
            preferences[SettingsKeys.LANGUAGE] = language.languageTag.orEmpty()
        }
    }

    override suspend fun updatePlaybackSettings(transform: (PlaybackSettings) -> PlaybackSettings) {
        context.settingsDataStore.edit { preferences -> preferences.applyPlaybackSettings(transform) }
    }

    // 两个互斥键同次编辑写完，不留中间态
    override suspend fun setUsbDirectOutput(enabled: Boolean) {
        context.saveUsbDirectOutputMode(enabled)
    }

    override suspend fun setUsbExclusiveOutput(enabled: Boolean) {
        context.saveUsbExclusiveOutputMode(enabled)
    }
}
