package com.yichao.evilgodxu.screens.home.component.queue

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.yichao.evilgodxu.ui.icons.AppIcons
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

// 最大露出位移 96dp，限制过度拖动
private val SWIPE_REVEAL_DP = 96.dp
// 触发阈值 56dp，未达即回弹
private val SWIPE_TRIGGER_DP = 56.dp
private const val SWIPE_SETTLE_MS = 180

/**
 * 播放列表项滑动容器：右滑露出高级菜单，左滑露出歌单归属。
 * 图标常驻内容之下，位移后露出；不带文案以免裁切。
 */
@Composable
internal fun TrackSwipeRow(
    onSwipePlaylist: () -> Unit,
    onSwipeAdvanced: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val revealPx = with(density) { SWIPE_REVEAL_DP.toPx() }
    val triggerPx = with(density) { SWIPE_TRIGGER_DP.toPx() }
    val offset = remember { Animatable(0f) }

    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        SwipeActionIcon(
            icon = AppIcons.AppRegistration,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            alignment = Alignment.CenterStart,
        )
        SwipeActionIcon(
            icon = AppIcons.Rule,
            tint = MaterialTheme.colorScheme.primary,
            alignment = Alignment.CenterEnd,
        )
        Box(
            modifier = Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                // 内容须不透明，否则图标静止时透出
                .background(MaterialTheme.colorScheme.surface)
                .pointerInput(revealPx, triggerPx) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val dragged = offset.value
                            scope.launch {
                                when {
                                    dragged >= triggerPx -> {
                                        offset.snapTo(0f)
                                        onSwipeAdvanced()
                                    }
                                    dragged <= -triggerPx -> {
                                        offset.snapTo(0f)
                                        onSwipePlaylist()
                                    }
                                    else -> offset.animateTo(0f, tween(SWIPE_SETTLE_MS))
                                }
                            }
                        },
                        onDragCancel = { scope.launch { offset.animateTo(0f, tween(SWIPE_SETTLE_MS)) } },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            scope.launch {
                                offset.snapTo((offset.value + dragAmount).coerceIn(-revealPx, revealPx))
                            }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

// 滑动露出的操作图标：贴在被露出的那一侧
@Composable
private fun SwipeActionIcon(
    icon: ImageVector,
    tint: Color,
    alignment: Alignment,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        contentAlignment = alignment,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
    }
}
