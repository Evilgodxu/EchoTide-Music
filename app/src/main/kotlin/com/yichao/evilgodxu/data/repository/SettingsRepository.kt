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
import com.yichao.evilgodxu.data.settings.writeBootLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

// 设置仓库契约：数据层入口，由 App 注入实现，单测注入内存 fake。
// ViewModel 只依赖本接口，不直接触碰 DataStore 与启动语言镜像
interface SettingsRepository {

    // 设置状态流：主题模式驱动全局配色
    val settings: Flow<SettingsState>

    // 应用语言流：统一读 DataStore
    val appLanguage: Flow<AppLanguage>

    // 播放相关设置：迷你播放器、歌词渲染、滑动切歌、背景流动与两路 USB 输出
    val playbackSettings: Flow<PlaybackSettings>

    suspend fun saveThemeMode(mode: ThemeMode)

    suspend fun getAppLanguage(): AppLanguage

    suspend fun setAppLanguage(language: AppLanguage)

    // 整组播放设置一次编辑落盘，调用方只改需要的字段
    suspend fun updatePlaybackSettings(transform: (PlaybackSettings) -> PlaybackSettings)

    // 两路 USB 输出互斥：开启一路必然关闭另一路
    suspend fun setUsbDirectOutput(enabled: Boolean)

    suspend fun setUsbExclusiveOutput(enabled: Boolean)
}

// DataStore 实现：以 DataStore 为设置项的唯一事实源。
// 上下文由构造注入，供启动语言镜像复用同一份存储位置
class DataStoreSettingsRepository(private val context: Context) : SettingsRepository {

    // 设置状态流：主题模式驱动全局配色。
    // DataStore 每次 edit 都会重发整份 Preferences（改任一字段都需整体重写），
    // 播放进度等高频写入会连带触发本流；按派生值去重，避免消费端被无效刷新
    override val settings: Flow<SettingsState> = context.settingsDataStore.data.map { preferences ->
        SettingsState(
            themeMode = ThemeMode.fromValue(preferences[SettingsKeys.THEME_MODE] ?: ThemeMode.SYSTEM.value),
        )
    }.distinctUntilChanged()

    // 按语言值去重，理由同上
    override val appLanguage: Flow<AppLanguage> = context.settingsDataStore.data.map { preferences ->
        AppLanguage.fromTag(preferences[SettingsKeys.LANGUAGE])
    }.distinctUntilChanged()

    // 播放设置同理：任一设置写入都会重发整份 Preferences，按快照去重避免无效刷新
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

    // 写入后由 Compose 层的语言流驱动热切换
    override suspend fun setAppLanguage(language: AppLanguage) {
        context.settingsDataStore.edit { preferences ->
            preferences[SettingsKeys.LANGUAGE] = language.languageTag.orEmpty()
        }
        // 同步刷新启动语言镜像：下次冷启动才无需读 DataStore 即可同步拿到刚选的语言
        withContext(Dispatchers.IO) { writeBootLanguage(context, language) }
    }

    override suspend fun updatePlaybackSettings(transform: (PlaybackSettings) -> PlaybackSettings) {
        context.settingsDataStore.edit { preferences -> preferences.applyPlaybackSettings(transform) }
    }

    // 复用设置模块的整组落盘：两路互斥的两个键在同一次编辑内写完，不留中间态
    override suspend fun setUsbDirectOutput(enabled: Boolean) {
        context.saveUsbDirectOutputMode(enabled)
    }

    override suspend fun setUsbExclusiveOutput(enabled: Boolean) {
        context.saveUsbExclusiveOutputMode(enabled)
    }
}
