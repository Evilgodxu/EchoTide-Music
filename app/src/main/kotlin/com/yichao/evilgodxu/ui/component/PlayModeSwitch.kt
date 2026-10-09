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
 * 播放模式切换按钮：按下后图标旋转一次并变为右向「>」，表示四种播放模式选项已就位；
 * 再次按下、选定模式或点击其他区域后旋转回当前模式的图标。
 *
 * 三处播放控制区（首页、音乐面板、悬浮窗迷你播放器）的按钮尺寸与配色各不相同，故只在此统一
 * 「旋转—换图标」的动画与无障碍语义，由各控制区按自身规格组装。
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
        // 旋转进度：展开 1、收起 0。两个图标共用同一进度，一个旋出淡出、另一个旋入淡入，
        // 读起来是「转一下换成了另一个图标」。收起后的图标取自当前播放模式，故选择模式与收起
        // 同帧发生时直接呈现新选中的图标
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
 * 展开后取代某个控制按钮的播放模式选项：点击即选中该模式并收起，恢复原控制按钮。
 * 当前模式以 [selectedTint] 与未选项区分，补偿收起态由切换按钮承担的模式示意。
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
 * 播放模式选择展开期间的「点击其他区域」遮罩：铺满可用区域并吞掉点击，命中即收起选择。
 *
 * 各控制区把它叠在背景内容之上、控制按钮之下：按钮位于上层照常响应，其余位置落到遮罩上收起。
 * 遮罩范围由调用方经 [modifier] 收窄（如剔除底部控制栏所占高度），故此处只负责铺满剩余区域。
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

// 触发图标旋转的时长：与右向「>」的出现同步，过快看不清旋转，过慢显得拖沓
private const val PLAY_MODE_TOGGLE_MS = 280
// 旋转幅度：整半圈，收起态的模式图标与展开态的右向「>」之间读起来是一次明确翻转
private const val PLAY_MODE_TOGGLE_DEGREES = 180f
