package com.yichao.evilgodxu.windowsize

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// 窗口宽度尺寸类：compact <600dp、medium 600-839dp、expanded >=840dp
enum class WindowSizeClass {
    Compact,
    Medium,
    Expanded,
}

// 单栏内容的行宽上限：宽窗收窄以免文本行过长，窄窗不设限。
// 阈值与形态判断同处一个文件，避免在多个组装器里各写一遍魔数
val WindowSizeClass.contentMaxWidth: Dp
    get() = if (this == WindowSizeClass.Compact) Dp.Unspecified else 720.dp

// 读取当前窗口宽度尺寸类；窗口尺寸变化时自动重组。
// 取窗口容器实测尺寸而非 Configuration.screenWidthDp：后者与 inset 行为耦合且被四舍五入，
// 不能准确反映实际可用窗口宽度。
// 只看宽度、不看朝向：横屏手机（宽度仍在 compact/medium 区间）按窄窗处理，内容满宽；
// 朝向只参与首页与播放面板的横竖形态分派（见 rememberExpandedForm）
@Composable
fun rememberWindowSizeClass(): WindowSizeClass {
    val width = LocalWindowInfo.current.containerDpSize.width
    return when {
        width >= 840.dp -> WindowSizeClass.Expanded
        width >= 600.dp -> WindowSizeClass.Medium
        else -> WindowSizeClass.Compact
    }
}

// 窗口是否横向：以实测宽高比为准；容器尺寸未就绪时回退到配置朝向
@Composable
fun rememberWindowLandscape(): Boolean {
    val windowDpSize = LocalWindowInfo.current.containerDpSize
    return windowDpSize.width > windowDpSize.height ||
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
}

// 宽屏形态判定：窗口横向，或窗口宽度达到宽屏断点。
// 首页与播放面板的横竖形态区块结构不同（竖屏 pager + 竖屏播放器 / 横屏侧栏歌单 + 横屏播放器），
// 故这两处保留按形态分派；其余页面用尺寸类的行宽上限表达差异
@Composable
fun rememberExpandedForm(): Boolean =
    rememberWindowLandscape() || rememberWindowSizeClass() == WindowSizeClass.Expanded
