package com.yichao.evilgodxu.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

// 首页路由键
@Serializable
data object Home : NavKey

@Serializable
data object Settings : NavKey

@Serializable
data object Typography : NavKey

@Serializable
data object Cache : NavKey

// 频谱分析页路由：只带曲目标识，曲目信息由页面自行解析。
// 同一曲目重复进入应复用同一路由，故以曲目标识参与相等性
@Serializable
data class Spectrum(val trackId: Long) : NavKey
