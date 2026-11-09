package com.yichao.evilgodxu.screens.settings

import com.yichao.evilgodxu.data.music.proxy.ProxySourceSpec
import com.yichao.evilgodxu.data.settings.AppLanguage
import com.yichao.evilgodxu.data.settings.PlaybackSettings
import com.yichao.evilgodxu.data.settings.ThemeMode

data class SettingsUiState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val language: AppLanguage = AppLanguage.SYSTEM,
    // 播放相关设置整组入状态：与仓库读的是同一个快照类型，不再逐字段搬运
    val playback: PlaybackSettings = PlaybackSettings(),
    // 迷你播放器开关已打开但悬浮窗权限尚未授予，正在等待用户从系统设置页返回
    val overlayPermissionPending: Boolean = false,
    // 已打开 USB 直出但免打扰访问权尚未授予，正在等待用户从免打扰授权页返回
    val notificationPolicyPermissionPending: Boolean = false,
    val version: String = "",
    val proxySources: List<ProxySourceSpec> = emptyList(),
    // 最近一次代理音源导入的提示信息（成功或失败原因），显示后自动清空
    val proxyImportMessage: String? = null,
    // 最近一次导入是否失败（决定提示文案颜色）
    val proxyImportFailed: Boolean = false,
    // 缓存合计占用：进入设置页采样一次，供缓存入口显示，明细在缓存页查看
    val cacheTotalBytes: Long = 0L,
)
