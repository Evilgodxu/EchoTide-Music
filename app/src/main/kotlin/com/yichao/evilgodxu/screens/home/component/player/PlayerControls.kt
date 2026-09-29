package com.yichao.evilgodxu.screens.home.component.player

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import kotlin.math.abs
import kotlinx.coroutines.launch

// 底部控制栏：与迷你播放器控件布局一致（播放模式 → 上一曲 → 播放/暂停 → 下一曲 → 播放列表）
@Composable
internal fun PlayerControls(
    playbackState: MusicPlaybackState,
    onPlaylistClick: () -> Unit,
    // 长按上一曲/下一曲唤出调速对话框：弹窗宿主上提至首页对话框层，不随控制栏隐藏而销毁
    onSpeedLongClick: () -> Unit,
    onPlaylistLongClick: () -> Unit = {},
    // 从播放/暂停按钮向上滑动：唤出音频信息弹窗；为 null 时该按钮保持普通点击行为
    onPlayPauseSwipeUp: (() -> Unit)? = null,
    // 从播放列表按钮向上滑动：打开播放列表面板；为 null 时该按钮保持普通点击行为
    onPlaylistSwipeUp: (() -> Unit)? = null,
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
            onSwipeUp = onPlayPauseSwipeUp,
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
            onSwipeUp = onPlaylistSwipeUp,
        )
    }
}

@Composable
private fun PlayerControlButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onSwipeUp: (() -> Unit)? = null,
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
    if (onSwipeUp != null) {
        val density = LocalDensity.current
        // 手势协程不随回调身份重建：以最新值读取，避免重建中断进行中的手势
        val currentOnSwipeUp = rememberUpdatedState(onSwipeUp)
        Box(
            modifier = Modifier
                .size(48.dp)
                // 点击与按压反馈仍由 combinedClickable 承担；无长按行为时传空回调而非省缺，
                // 静默吞掉长按后的抬手，避免误触发单击动作
                .combinedClickable(
                    enabled = enabled,
                    onClick = onClick,
                    onLongClick = onLongClick ?: {},
                )
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectSwipeUp(
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
 * 控制栏按钮上滑的手势判定：纵向向上主导、且累计上滑超过 [thresholdPx] 时触发 [onSwipeUp]。
 *
 * 越过触摸阈值前不消费任何事件，方向由位移判定：横向主导让给左右翻页，
 * 纵向向下让给整页纵向切歌手势；判为向上即接管本次手势（消费位移与抬手），
 * 上层纵向切歌手势据此让出，一次滑动不会触发两个动作。
 * 越过上滑距离即唤出，不等抬手——手势已由本按钮接管，提前响应对手势距离更宽容。
 * 未构成滑动的手势不被消费，点击与按压反馈照常由 [combinedClickable] 处理。
 */
private suspend fun PointerInputScope.detectSwipeUp(
    thresholdPx: Float,
    onSwipeUp: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var accX = 0f
        var accY = 0f
        // 是否已判定为向上滑动并接管本次手势
        var claimed = false
        // 是否已唤出：一次手势只触发一次
        var fired = false
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            // 已被上层手势接管时让出，避免同一次滑动触发两个动作
            if (change.isConsumed) break
            accX += change.positionChange().x
            accY += change.positionChange().y
            if (!claimed) {
                val slop = viewConfiguration.touchSlop
                if (abs(accX) >= slop || abs(accY) >= slop) {
                    // 仅在纵向向上主导时接管，其余方向原样放行
                    if (accY < 0f && abs(accY) > abs(accX)) claimed = true else break
                }
            }
            if (claimed) {
                change.consume()
                if (!fired && accY <= -thresholdPx) {
                    fired = true
                    onSwipeUp()
                }
            }
            if (!change.pressed) break
        }
    }
}

// 按钮上滑唤出弹层所需的最小上升距离：方向判定已由系统触摸阈值把关，此处取其两倍量级，
// 排除轻扫抖动，同时保证一次常规上滑即可唤出
private val SWIPE_UP_TRIGGER_DISTANCE = 32.dp
