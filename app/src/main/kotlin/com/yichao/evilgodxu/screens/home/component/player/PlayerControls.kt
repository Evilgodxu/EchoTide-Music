package com.yichao.evilgodxu.screens.home.component.player

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yichao.evilgodxu.data.music.model.PlayMode
import com.yichao.evilgodxu.data.music.playback.applyPlaybackMode
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.data.music.playback.playTrackAt
import com.yichao.evilgodxu.data.music.playback.togglePlayPause
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.ui.icons.AppIcons
import kotlinx.coroutines.launch

// 底部控制栏：与迷你播放器控件布局一致（播放模式 → 上一曲 → 播放/暂停 → 下一曲 → 播放列表）
@Composable
internal fun PlayerControls(
    playbackState: MusicPlaybackState,
    onPlaylistClick: () -> Unit,
    // 长按上一曲/下一曲唤出调速对话框：弹窗宿主上提至首页对话框层，不随控制栏隐藏而销毁
    onSpeedLongClick: () -> Unit,
    onPlaylistLongClick: () -> Unit = {},
    // 长按播放/暂停后上滑：唤出音频信息弹窗；为 null 时该按钮保持普通点击行为
    onPlayPauseSwipeUp: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerControlButton(
            icon = when (playbackState.playMode) {
                PlayMode.RepeatAll -> AppIcons.Repeat
                PlayMode.RepeatOne -> AppIcons.RepeatOne
                PlayMode.Shuffle -> AppIcons.Shuffle
            },
            contentDescription = stringResource(R.string.music_panel_play_mode),
            onClick = {
                playbackState.setPlayMode(
                    when (playbackState.playMode) {
                        PlayMode.RepeatAll -> PlayMode.RepeatOne
                        PlayMode.RepeatOne -> PlayMode.Shuffle
                        PlayMode.Shuffle -> PlayMode.RepeatAll
                    }
                )
                playbackState.mediaController?.let { controller ->
                    applyPlaybackMode(controller, playbackState.playMode)
                }
                playbackState.persistState()
            },
        )
        PlayerControlButton(
            icon = AppIcons.SkipPrevious,
            contentDescription = stringResource(R.string.home_player_previous),
            enabled = playbackState.playlist.isNotEmpty(),
            onClick = {
                val prev = playbackState.previousIndex()
                if (prev >= 0) scope.launch { playTrackAt(context, playbackState, prev) }
            },
            onLongClick = onSpeedLongClick,
        )
        PlayerControlButton(
            icon = if (playbackState.isPlaying) AppIcons.Pause else AppIcons.PlayArrow,
            contentDescription = stringResource(
                if (playbackState.isPlaying) R.string.home_player_pause else R.string.home_player_play
            ),
            enabled = playbackState.playlist.isNotEmpty(),
            onClick = { togglePlayPause(playbackState) },
            onLongPressSwipeUp = onPlayPauseSwipeUp,
        )
        PlayerControlButton(
            icon = AppIcons.SkipNext,
            contentDescription = stringResource(R.string.home_player_next),
            enabled = playbackState.playlist.isNotEmpty(),
            onClick = {
                val next = playbackState.nextIndex()
                if (next >= 0) scope.launch { playTrackAt(context, playbackState, next) }
            },
            onLongClick = onSpeedLongClick,
        )
        PlayerControlButton(
            icon = AppIcons.QueueMusic,
            contentDescription = stringResource(R.string.music_panel_playlist),
            onClick = onPlaylistClick,
            onLongClick = onPlaylistLongClick,
        )
    }
}

@Composable
private fun PlayerControlButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onLongPressSwipeUp: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    val tint = if (enabled) Color.White else Color.White.copy(alpha = 0.3f)
    val iconContent = @Composable {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(32.dp),
            tint = tint,
        )
    }
    if (onLongPressSwipeUp != null) {
        val density = LocalDensity.current
        // 手势协程不随回调身份重建：以最新值读取，避免重建中断进行中的手势
        val currentOnSwipeUp = rememberUpdatedState(onLongPressSwipeUp)
        Box(
            modifier = Modifier
                .size(48.dp)
                // 点击与按压反馈仍由 combinedClickable 承担；长按不触发任何动作（空回调），
                // 仅静默吞掉点击，把长按后的动作留给上滑手势判定
                .combinedClickable(
                    enabled = enabled,
                    onClick = onClick,
                    onLongClick = {},
                )
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectLongPressSwipeUp(
                        thresholdPx = with(density) { SWIPE_UP_TRIGGER_DISTANCE.toPx() },
                        onSwipeUp = { currentOnSwipeUp.value() },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            iconContent()
        }
    } else if (onLongClick == null) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(48.dp),
        ) {
            iconContent()
        }
    } else {
        // 长按支持：单击保留原行为，长按触发 onLongClick
        Box(
            modifier = Modifier
                .size(48.dp)
                .combinedClickable(
                    enabled = enabled,
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            iconContent()
        }
    }
}

/**
 * 长按后上滑的手势判定：长按成立后跟踪纵向拖动，累计上滑超过 [thresholdPx] 才触发 [onSwipeUp]。
 *
 * 与 [combinedClickable] 叠于同一节点：本手势位于修饰符链内侧，先于点击处理收到事件，
 * 长按成立后消费位移与抬手，点击侧据此不再触发，长按后的动作只由本手势决定。
 */
private suspend fun PointerInputScope.detectLongPressSwipeUp(
    thresholdPx: Float,
    onSwipeUp: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (awaitLongPressOrCancellation(down.id) == null) return@awaitEachGesture
        var totalDy = 0f
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            // 已被上层手势接管（例如纵向切歌）时让出，避免同一次滑动触发两个动作
            if (change.isConsumed) break
            totalDy += change.positionChange().y
            change.consume()
            if (!change.pressed) break
        }
        if (totalDy <= -thresholdPx) onSwipeUp()
    }
}

// 长按后触发上滑所需的最小上升距离：与触摸阈值同量级，避免轻微抖动即唤出弹窗
private val SWIPE_UP_TRIGGER_DISTANCE = 40.dp
