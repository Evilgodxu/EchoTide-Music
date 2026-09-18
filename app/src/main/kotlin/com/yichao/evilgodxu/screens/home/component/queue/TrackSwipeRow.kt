package com.yichao.evilgodxu.screens.home.component.queue

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.ui.icons.AppIcons
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

// 滑动可露出的最大位移：限制过度拖动，超出部分不再跟手
private val SWIPE_REVEAL_DP = 96.dp
// 触发操作的最小位移：松手时未达阈值即回弹
private val SWIPE_TRIGGER_DP = 56.dp
// 回弹动画时长
private const val SWIPE_SETTLE_MS = 180

/**
 * 播放列表项滑动容器：右滑露出并触发高级菜单，左滑露出并触发加入黑名单。
 *
 * 两侧图标常驻在内容之下，只在内容位移后露出，因此滑动过程中即可预判释放后的动作。
 */
@Composable
internal fun TrackSwipeRow(
    onSwipeBlacklist: () -> Unit,
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
            icon = AppIcons.MoreVert,
            label = stringResource(R.string.playlist_swipe_advanced),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            alignment = Alignment.CenterStart,
        )
        SwipeActionIcon(
            icon = AppIcons.Block,
            label = stringResource(R.string.playlist_swipe_blacklist),
            tint = MaterialTheme.colorScheme.error,
            alignment = Alignment.CenterEnd,
        )
        Box(
            modifier = Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                // 内容需不透明：两侧图标位于内容之下，透明底会让图标在静止时透出
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
                                        onSwipeBlacklist()
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

// 滑动露出的操作图标：图标与文案横向排列，贴在被露出的那一侧
@Composable
private fun SwipeActionIcon(
    icon: ImageVector,
    label: String,
    tint: Color,
    alignment: Alignment,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        contentAlignment = alignment,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = label,
                color = tint,
                fontSize = 11.sp,
            )
        }
    }
}

/**
 * 高级菜单对话框：条目留空，仅提供容器与关闭入口，后续按需补充操作项。
 */
@Composable
internal fun TrackAdvancedMenuDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                .padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.playlist_advanced_menu_title),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.playlist_advanced_menu_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
            Text(
                text = stringResource(R.string.playlist_advanced_menu_close),
                color = MaterialTheme.colorScheme.primary,
                fontSize = 14.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
    }
}