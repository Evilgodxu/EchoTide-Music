package com.yichao.evilgodxu.screens.settings

import com.yichao.evilgodxu.data.music.proxy.ProxySourceSpec
import com.yichao.evilgodxu.data.settings.AppLanguage
import com.yichao.evilgodxu.data.settings.ThemeMode

data class SettingsUiState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val language: AppLanguage = AppLanguage.SYSTEM,
    val miniPlayerEnabled: Boolean = false,
    // 迷你播放器开关已打开但悬浮窗权限尚未授予，正在等待用户从系统设置页返回
    val overlayPermissionPending: Boolean = false,
    val wordByWordRendering: Boolean = true,
    val swipeToChangeTrack: Boolean = true,
    // 背景流动：开启后首页封面衍生背景按固定默认值缓慢流动，关闭时只渲染一帧
    val backgroundFlow: Boolean = false,
    // USB 直出：开启后按设备声明的条目为播放申请止步于源格式的专用输出流
    val usbDirectOutput: Boolean = false,
    // USB 独占：开启后接管 USB 音频接口自行写出，期间该解码器从系统音频中消失；与直出互斥
    val usbExclusiveOutput: Boolean = false,
    val version: String = "",
    val proxySources: List<ProxySourceSpec> = emptyList(),
    // 最近一次代理音源导入的提示信息（成功或失败原因），显示后自动清空
    val proxyImportMessage: String? = null,
    // 最近一次导入是否失败（决定提示文案颜色）
    val proxyImportFailed: Boolean = false,
    // 缓存合计占用：进入设置页采样一次，供缓存入口显示，明细在缓存页查看
    val cacheTotalBytes: Long = 0L,
)
