package com.yichao.evilgodxu.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.data.music.model.PlayMode
import com.yichao.evilgodxu.ui.icons.AppIcons

/**
 * 播放模式切换钮：展开时图标旋转成右箭头，收起还原
 */
@Composable
internal fun PlayModeToggleButton(
    playMode: PlayMode,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    size: Dp,
    iconSize: Dp,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(size)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onExpandedChange(!expanded) },
        contentAlignment = Alignment.Center,
    ) {
        // 两个图标共用进度：一个旋出，一个旋入
        val progress by animateFloatAsState(
            targetValue = if (expanded) 1f else 0f,
            animationSpec = tween(PLAY_MODE_TOGGLE_MS),
            label = "playModeToggle",
        )
        val description = stringResource(R.string.music_panel_play_mode)
        Icon(
            imageVector = playModeIcon(playMode),
            contentDescription = if (expanded) null else description,
            modifier = Modifier
                .size(iconSize)
                .graphicsLayer {
                    alpha = 1f - progress
                    rotationZ = progress * PLAY_MODE_TOGGLE_DEGREES
                },
            tint = tint,
        )
        Icon(
            imageVector = AppIcons.KeyboardArrowRight,
            contentDescription = if (expanded) description else null,
            modifier = Modifier
                .size(iconSize)
                .graphicsLayer {
                    alpha = progress
                    rotationZ = (progress - 1f) * PLAY_MODE_TOGGLE_DEGREES
                },
            tint = tint,
        )
    }
}

/**
 * 模式选项，当前模式单独着色
 */
@Composable
internal fun PlayModeOptionButton(
    mode: PlayMode,
    selected: Boolean,
    onClick: () -> Unit,
    size: Dp,
    iconSize: Dp,
    tint: Color,
    selectedTint: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(size)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = playModeIcon(mode),
            contentDescription = stringResource(playModeLabelRes(mode)),
            modifier = Modifier.size(iconSize),
            tint = if (selected) selectedTint else tint,
        )
    }
}

/**
 * 展开期间的点击遮罩，命中即收起
 */
@Composable
internal fun PlayModeSelectionScrim(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    Box(
        modifier = modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
    )
}

// 旋转 280ms，与箭头出现同步
private const val PLAY_MODE_TOGGLE_MS = 280
// 旋转 180°，读作一次明确翻转
private const val PLAY_MODE_TOGGLE_DEGREES = 180f
