package com.yichao.evilgodxu.windowsize

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// 宽度尺寸类：<600 / 600-839 / ≥840dp
enum class WindowSizeClass {
    Compact,
    Medium,
    Expanded,
}

// 行宽上限：宽窗 720dp，窄窗不设限
val WindowSizeClass.contentMaxWidth: Dp
    get() = if (this == WindowSizeClass.Compact) Dp.Unspecified else 720.dp

// 按窗口容器实测宽度取尺寸类，不看朝向
@Composable
fun rememberWindowSizeClass(): WindowSizeClass {
    val width = LocalWindowInfo.current.containerDpSize.width
    return when {
        width >= 840.dp -> WindowSizeClass.Expanded
        width >= 600.dp -> WindowSizeClass.Medium
        else -> WindowSizeClass.Compact
    }
}

// 实测宽高比判横屏；未就绪回退配置
@Composable
fun rememberWindowLandscape(): Boolean {
    val windowDpSize = LocalWindowInfo.current.containerDpSize
    return windowDpSize.width > windowDpSize.height ||
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
}

// 宽屏形态：横屏或宽度达 expanded 断点
@Composable
fun rememberExpandedForm(): Boolean =
    rememberWindowLandscape() || rememberWindowSizeClass() == WindowSizeClass.Expanded
