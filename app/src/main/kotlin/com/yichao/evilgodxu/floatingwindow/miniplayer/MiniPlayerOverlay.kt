package com.yichao.evilgodxu.floatingwindow.miniplayer

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.data.settings.ThemeMode
import com.yichao.evilgodxu.LocalSettingsRepository
import com.yichao.evilgodxu.theme.DarkColorScheme
import com.yichao.evilgodxu.theme.LightColorScheme

@Composable
internal fun MiniPlayerOverlay(
    playbackState: MusicPlaybackState,
    barHeightPx: Int,
    barWidthPx: Int,
    onOpenPlaylist: () -> Unit,
    onExpandPanel: () -> Unit,
    onSwipeDismiss: () -> Unit,
) {
    val density = LocalDensity.current
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
    val settings by LocalSettingsRepository.current.settings.collectAsStateWithLifecycle(initialValue = null)
    val isSystemDark = isSystemInDarkTheme()
    val isDarkTheme = when (settings?.themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        else -> isSystemDark
    }
    val colorScheme = if (isDarkTheme) DarkColorScheme else LightColorScheme
    val cardBackground = Color(if (isDarkTheme) 0xFF161B22 else 0xFFF5F5F7)
        .copy(alpha = if (isDarkTheme) 0.55f else 0.60f)

    MaterialTheme(colorScheme = colorScheme) {
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
                onOpenPlaylist = onOpenPlaylist,
                onExpandPanel = onExpandPanel,
                swipeTrackThreshold = barWidthPx / 2f,
                onSwipeOffsetChange = { swipeOffset = it },
                onSwipeCancel = { swipeOffset = 0f },
                onSwipeDown = onSwipeDismiss,
            )
        }
    }
}