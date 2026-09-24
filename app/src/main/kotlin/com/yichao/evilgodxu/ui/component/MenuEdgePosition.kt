package com.yichao.evilgodxu.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider

// 锚定式菜单的定位：水平居中于锚点，纵向紧贴锚点的上缘或下缘（间隔 2dp）。
// atTop 取贴哪条边，insideAnchor 取菜单落在锚点之外还是之内——落在锚点内时自所贴的边向对侧展开，
// 供菜单需压在锚点内容上的场景使用（如频谱图导出菜单在图内自底向上弹出）。
// 播放器封面、频谱图等处的长按菜单共用同一套定位规则
@Composable
internal fun menuEdgePositionProvider(
    atTop: Boolean,
    insideAnchor: Boolean = false,
): PopupPositionProvider {
    val density = LocalDensity.current
    return remember(density, atTop, insideAnchor) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val gapPx = with(density) { 2.dp.roundToPx() }
                val y = when {
                    !insideAnchor && atTop -> anchorBounds.top - popupContentSize.height - gapPx
                    !insideAnchor -> anchorBounds.bottom + gapPx
                    atTop -> anchorBounds.top + gapPx
                    else -> anchorBounds.bottom - popupContentSize.height - gapPx
                }
                return IntOffset(
                    x = anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2,
                    y = y,
                )
            }
        }
    }
}
