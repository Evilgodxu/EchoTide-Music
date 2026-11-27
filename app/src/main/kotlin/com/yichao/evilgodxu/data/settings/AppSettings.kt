package com.yichao.evilgodxu.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.yichao.evilgodxu.data.persistence.PersistedData
import com.yichao.evilgodxu.data.persistence.PersistedFileKind
import com.yichao.evilgodxu.log.CrashLogManager

// 设置存储落点，名字即持久化标识
internal val SETTINGS_STORE = PersistedData("settings", PersistedFileKind.DATA_STORE)

// 损坏时重置为空配置并记日志
val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = SETTINGS_STORE.name,
    corruptionHandler = ReplaceFileCorruptionHandler<Preferences> { throwable ->
        CrashLogManager.logException("SettingsDataStore", "设置数据损坏，已重置为默认值", throwable)
        emptyPreferences()
    },
)

object SettingsKeys {
    val THEME_MODE = stringPreferencesKey("theme_mode")
    // 语言落 DataStore，驱动热切换
    val LANGUAGE = stringPreferencesKey("language")
    // 上次选中的搜索平台
    val SEARCH_SOURCE = stringPreferencesKey("search_source")
}

enum class ThemeMode(val value: String) {
    SYSTEM("system"),
    DARK("dark"),
    LIGHT("light");

    companion object {
        fun fromValue(value: String): ThemeMode = entries.find { it.value == value } ?: SYSTEM
    }
}

enum class AppLanguage(val languageTag: String?) {
    SYSTEM(null),
    CHINESE("zh"),
    ENGLISH("en");

    companion object {
        // 未知值或缺键回落到跟随系统
        fun fromTag(tag: String?): AppLanguage = entries.find { it.languageTag == tag } ?: SYSTEM
    }
}

data class SettingsState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
)
