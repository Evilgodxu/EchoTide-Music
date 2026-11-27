package com.yichao.evilgodxu.navigation

import androidx.compose.runtime.compositionLocalOf
import java.io.Serializable

// 播放页是否为最上层；被压住时页内返回须让出
val LocalIsTopLayer = compositionLocalOf { true }

// 页面身份：同一时刻仅一个覆盖层，无栈
sealed interface AppDestination : Serializable

data object Home : AppDestination

data object Settings : AppDestination

// 频谱页：只带曲目标识，页面自行解析
data class Spectrum(val trackId: Long) : AppDestination

// 元数据页：只带曲目标识，标签由页面读取
data class Metadata(val trackId: Long) : AppDestination
