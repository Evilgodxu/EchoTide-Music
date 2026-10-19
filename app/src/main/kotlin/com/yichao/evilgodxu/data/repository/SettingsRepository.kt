package com.yichao.evilgodxu.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.yichao.evilgodxu.data.settings.AppLanguage
import com.yichao.evilgodxu.data.settings.settingsDataStore
import com.yichao.evilgodxu.data.settings.SettingsKeys
import com.yichao.evilgodxu.data.settings.SettingsState
import com.yichao.evilgodxu.data.settings.ThemeMode
import com.yichao.evilgodxu.data.settings.writeBootLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class SettingsRepository(private val context: Context) {

    // 设置状态流：主题模式驱动全局配色。
    // DataStore 每次 edit 都会重发整份 Preferences（改任一字段都需整体重写），
    // 播放进度等高频写入会连带触发本流；按派生值去重，避免消费端被无效刷新
    val settings: Flow<SettingsState> = context.settingsDataStore.data.map { preferences ->
        SettingsState(
            themeMode = ThemeMode.fromValue(preferences[SettingsKeys.THEME_MODE] ?: ThemeMode.SYSTEM.value),
        )
    }.distinctUntilChanged()

    // 按语言值去重，理由同上
    val appLanguage: Flow<AppLanguage> = context.settingsDataStore.data.map { preferences ->
        AppLanguage.entries.find { it.languageTag == preferences[SettingsKeys.LANGUAGE] } ?: AppLanguage.SYSTEM
    }.distinctUntilChanged()

    suspend fun saveThemeMode(mode: ThemeMode) {
        context.settingsDataStore.edit { preferences ->
            preferences[SettingsKeys.THEME_MODE] = mode.value
        }
    }

    suspend fun getAppLanguage(): AppLanguage {
        return context.settingsDataStore.data.first().let { preferences ->
            AppLanguage.entries.find { it.languageTag == preferences[SettingsKeys.LANGUAGE] } ?: AppLanguage.SYSTEM
        }
    }

    // 写入后由 Compose 层的语言流驱动热切换
    suspend fun setAppLanguage(language: AppLanguage) {
        context.settingsDataStore.edit { preferences ->
            preferences[SettingsKeys.LANGUAGE] = language.languageTag.orEmpty()
        }
        // 同步刷新启动语言镜像：下次冷启动才无需读 DataStore 即可同步拿到刚选的语言
        withContext(Dispatchers.IO) { writeBootLanguage(context, language) }
    }
}
