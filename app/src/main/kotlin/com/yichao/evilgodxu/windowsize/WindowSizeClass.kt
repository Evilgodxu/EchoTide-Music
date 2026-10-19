package com.yichao.evilgodxu.windowsize

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.window.core.layout.computeWindowSizeClass
import androidx.window.core.layout.WindowSizeClass

val LocalWindowSizeClass = compositionLocalOf<WindowSizeClass> {
    error("WindowSizeClass not provided")
}

// 取窗口实测 dp 尺寸计算：该值随窗口尺寸变化自动刷新，且不注册窗口监听，避免校验设备崩溃
@Composable
fun ProvideWindowSizeClass(content: @Composable () -> Unit) {
    val windowDpSize = LocalWindowInfo.current.containerDpSize
    val windowSizeClass = WindowSizeClass.BREAKPOINTS_V1.computeWindowSizeClass(
        windowDpSize.width.value,
        windowDpSize.height.value,
    )
    CompositionLocalProvider(LocalWindowSizeClass provides windowSizeClass) {
        content()
    }
}

// 以窗口实测宽高比为准；容器尺寸未就绪时回退到配置朝向
@Composable
fun rememberWindowLandscape(): Boolean {
    val windowDpSize = LocalWindowInfo.current.containerDpSize
    return windowDpSize.width > windowDpSize.height ||
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
}

// 宽屏形态判定：窗口横向，或窗口宽度达到宽屏断点；供页面按形态分派组装器
@Composable
fun rememberExpandedForm(): Boolean {
    val windowSizeClass = LocalWindowSizeClass.current
    return rememberWindowLandscape() ||
        windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)
}
