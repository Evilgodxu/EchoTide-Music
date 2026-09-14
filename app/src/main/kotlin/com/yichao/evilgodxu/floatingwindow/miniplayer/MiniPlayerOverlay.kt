package com.yichao.evilgodxu.floatingwindow.miniplayer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yichao.evilgodxu.data.settings.settingsFlow
import com.yichao.evilgodxu.data.settings.ThemeMode
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.theme.DarkColorScheme
import com.yichao.evilgodxu.theme.LightColorScheme

@Composable
internal fun MiniPlayerOverlay(
    playbackState: MusicPlaybackState,
    barHeightPx: Int,
    barWidthPx: Int,
    playlistExpanded: Boolean,
    visualExpanded: Boolean,
    onPlaylistExpandedChange: (Boolean) -> Unit,
    onLayoutChanged: () -> Unit,
    onCollapseAnimationEnd: () -> Unit,
    onExpandPanel: () -> Unit,
    onSwipeDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    val context = LocalContext.current
    val barHeight = with(density) { barHeightPx.toDp() }
    val barWidth = with(density) { barWidthPx.toDp() }

    // 左右滑动切歌：拖动时条跟随手指，抬手后回弹（切歌由手势侧直接触发，无滑出动画）
    var swipeOffset by remember { mutableFloatStateOf(0f) }
    val swipeTranslate by animateFloatAsState(
        targetValue = swipeOffset,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "mini_player_swipe_offset"
    )

    // 跟随应用主题：设置项优先，其次系统深色模式
    val settings by context.settingsFlow().collectAsStateWithLifecycle(initialValue = null)
    val isSystemDark = isSystemInDarkTheme()
    val isDarkTheme = when (settings?.themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        else -> isSystemDark
    }
    val colorScheme = if (isDarkTheme) DarkColorScheme else LightColorScheme
    // 展开缩放动画完成信号：每次展开动画结束自增，通知播放列表延迟定位当前曲目，
    // 避免滚动动画与缩放动画叠加导致首帧卡顿
    var playlistReady by remember { mutableIntStateOf(0) }
    // 卡片背景：收起时高透明透出下层内容，展开播放列表时降低透明度保证列表可读性
    // 跟随视觉展开状态：收起动画期间保持不透明，动画结束切回紧凑条后再恢复高透明
    val cardBackground = if (visualExpanded) {
        Color(if (isDarkTheme) 0xFF161B22 else 0xFFF5F5F7).copy(alpha = 0.92f)
    } else {
        Color(if (isDarkTheme) 0xFF161B22 else 0xFFF5F5F7).copy(alpha = if (isDarkTheme) 0.55f else 0.60f)
    }

    // 屏幕旋转时自动收起播放列表并重新布局；旋转时跳过收起缩放动画，直接恢复紧凑窗口
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    LaunchedEffect(configuration.orientation) {
        if (playlistExpanded) {
            onPlaylistExpandedChange(false)
            onCollapseAnimationEnd()
        }
        onLayoutChanged()
    }

    MaterialTheme(colorScheme = colorScheme) {
        if (visualExpanded) {
            // 展开/收起缩放动画：进入组合时卡片从 0.85 放大到 1.0；收起时反向缩小后恢复紧凑窗口
            val cardScale = remember { Animatable(0.85f) }
            LaunchedEffect(playlistExpanded) {
                if (playlistExpanded) {
                    // 重置展开就绪信号：本轮展开必须等缩放动画完成才允许列表定位当前曲目
                    playlistReady = 0
                    cardScale.snapTo(0.85f)
                    cardScale.animateTo(
                        targetValue = 1f,
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                    )
                    // 缩放动画结束：通知播放列表定位当前曲目，避免滚动动画与缩放叠加卡顿
                    playlistReady++
                } else {
                    cardScale.animateTo(
                        targetValue = 0.85f,
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                    )
                    onCollapseAnimationEnd()
                }
            }
            // 展开时窗口铺满屏幕，卡片以外区域点击即收起
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onPlaylistExpandedChange(false) }
                    )
            ) {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .width(barWidth)
                        .graphicsLayer {
                            scaleX = cardScale.value
                            scaleY = cardScale.value
                        }
                        // 展开播放列表时用小圆角，呈现更接近窗口的方角效果
                        .background(cardBackground, RoundedCornerShape(12.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { /* 阻止点击穿透到收起区域 */ }
                        )
                ) {
                    MiniPlayerBar(
                        playbackState = playbackState,
                        barHeight = barHeight,
                        playlistExpanded = playlistExpanded,
                        onPlaylistExpandedChange = onPlaylistExpandedChange,
                        onExpandPanel = onExpandPanel,
                        swipeTrackThreshold = barWidthPx / 2f,
                        onSwipeOffsetChange = { swipeOffset = it },
                        onSwipeCancel = { swipeOffset = 0f },
                        onSwipeDown = onSwipeDismiss,
                    )
                    MiniPlaylistPanel(
                        playbackState = playbackState,
                        context = context,
                        scrollReady = playlistReady,
                        onClose = { onPlaylistExpandedChange(false) }
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .width(barWidth)
                    .graphicsLayer { translationX = swipeTranslate }
                    // 胶囊圆角：半径取条高（32dp）一半，呈椭圆轮廓
                    .background(cardBackground, RoundedCornerShape(16.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { /* 阻止点击穿透到状态栏区域 */ }
                    )
            ) {
                MiniPlayerBar(
                    playbackState = playbackState,
                    barHeight = barHeight,
                    playlistExpanded = playlistExpanded,
                    onPlaylistExpandedChange = onPlaylistExpandedChange,
                    onExpandPanel = onExpandPanel,
                    swipeTrackThreshold = barWidthPx / 2f,
                    onSwipeOffsetChange = { swipeOffset = it },
                    onSwipeCancel = { swipeOffset = 0f },
                    onSwipeDown = onSwipeDismiss,
                )
            }
        }
    }
}