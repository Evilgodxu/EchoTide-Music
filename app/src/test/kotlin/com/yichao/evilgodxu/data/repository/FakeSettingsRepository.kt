package com.yichao.evilgodxu.data.repository

import com.yichao.evilgodxu.data.settings.AppLanguage
import com.yichao.evilgodxu.data.settings.SettingsState
import com.yichao.evilgodxu.data.settings.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

// 内存版设置仓库：单测用它替代真实 DataStore，不经磁盘也不另引 mock 框架
class FakeSettingsRepository(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    language: AppLanguage = AppLanguage.SYSTEM,
) : SettingsRepository {

    private val settingsState = MutableStateFlow(SettingsState(themeMode = themeMode))
    private val languageState = MutableStateFlow(language)

    override val settings: Flow<SettingsState> = settingsState

    override val appLanguage: Flow<AppLanguage> = languageState

    override suspend fun saveThemeMode(mode: ThemeMode) {
        settingsState.value = SettingsState(themeMode = mode)
    }

    override suspend fun getAppLanguage(): AppLanguage = languageState.value

    override suspend fun setAppLanguage(language: AppLanguage) {
        languageState.value = language
    }
}
