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

// 菜单居中于锚点贴边，2dp 间隔，可向内展开
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
