package com.yichao.evilgodxu.screens.settings

import com.yichao.evilgodxu.data.music.proxy.ProxySourceSpec
import com.yichao.evilgodxu.data.settings.AppLanguage
import com.yichao.evilgodxu.data.settings.PlaybackSettings
import com.yichao.evilgodxu.data.settings.ThemeMode

/** 设置页内层级：换层级只换标题与内容 */
enum class SettingsPage {
    ROOT,
    APPEARANCE_LANGUAGE,
    PLAYBACK_MODE,
    TYPOGRAPHY,
    PROXY_SOURCE,
    STORAGE,
}

data class SettingsUiState(
    // 当前层级：返回先退层，根页再交导航栈
    val page: SettingsPage = SettingsPage.ROOT,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val language: AppLanguage = AppLanguage.SYSTEM,
    // 播放设置整组入状态
    val playback: PlaybackSettings = PlaybackSettings(),
    // 等待用户从悬浮窗授权页返回
    val overlayPermissionPending: Boolean = false,
    // 等待用户从免打扰授权页返回
    val notificationPolicyPermissionPending: Boolean = false,
    val version: String = "",
    val proxySources: List<ProxySourceSpec> = emptyList(),
    // 导入提示，显示后自动清空
    val proxyImportMessage: String? = null,
    // 导入是否失败，决定提示颜色
    val proxyImportFailed: Boolean = false,
    // 缓存合计，供入口显示
    val cacheTotalBytes: Long = 0L,
)
