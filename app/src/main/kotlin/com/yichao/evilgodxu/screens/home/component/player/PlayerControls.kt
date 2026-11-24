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
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.data.music.playback.TrackSwitchKind
import com.yichao.evilgodxu.data.music.playback.playTrackAt
import com.yichao.evilgodxu.data.music.playback.togglePlayPause
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.ui.component.playModeMenuOrder
import com.yichao.evilgodxu.ui.component.PlayModeOptionButton
import com.yichao.evilgodxu.ui.component.PlayModeToggleButton
import com.yichao.evilgodxu.ui.component.selectPlayMode
import com.yichao.evilgodxu.ui.icons.AppIcons
import kotlin.math.abs
import kotlinx.coroutines.launch

// 底部控制栏：与迷你播放器控件布局一致（播放模式 → 上一曲 → 播放/暂停 → 下一曲 → 播放列表）
@Composable
internal fun PlayerControls(
    playbackState: MusicPlaybackState,
    // 播放模式选择展开态：由所在播放器页持有，展开时四个控制按钮让位于四种播放模式
    modeSelectorExpanded: Boolean,
    onModeSelectorExpandedChange: (Boolean) -> Unit,
    onPlaylistClick: () -> Unit,
    onPlaylistLongClick: () -> Unit = {},
    // 从播放/暂停按钮向上滑动：唤出音频信息弹窗；为 null 时该按钮保持普通点击行为
    onPlayPauseSwipeUp: (() -> Unit)? = null,
    // 从播放列表按钮向上滑动：打开播放列表面板；为 null 时该按钮保持普通点击行为
    onPlaylistSwipeUp: (() -> Unit)? = null,
    // 音量调节进行态：起手至松手期间为 true，供宿主在调节期间暂缓自身的自动收起
    onVolumeAdjustingChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 播放/暂停按钮上的左右滑动：调节音量，浮层跟随手指横向位置
    val volumeSwipe = rememberVolumeSwipeState(playbackState)
    // 手势协程只建立一次，进行态回调以最新值读取
    val currentOnVolumeAdjusting = rememberUpdatedState(onVolumeAdjustingChange)
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayModeToggleButton(
                playMode = playbackState.playMode,
                expanded = modeSelectorExpanded,
                onExpandedChange = onModeSelectorExpandedChange,
                size = 48.dp,
                iconSize = 32.dp,
                tint = Color.White,
            )
            if (modeSelectorExpanded) {
                // 展开期间四个控制按钮让位给四种播放模式：选中即切换模式并收起，原按钮随后复位
                playModeMenuOrder.forEach { mode ->
                    PlayModeOptionButton(
                        mode = mode,
                        selected = mode == playbackState.playMode,
                        onClick = {
                            selectPlayMode(playbackState, mode)
                            onModeSelectorExpandedChange(false)
                        },
                        size = 48.dp,
                        iconSize = 32.dp,
                        tint = Color.White.copy(alpha = 0.55f),
                        selectedTint = Color.White,
                    )
                }
            } else {
                PlayerControlButton(
                    icon = AppIcons.SkipPrevious,
                    contentDescription = stringResource(R.string.home_player_previous),
                    enabled = playbackState.playlist.isNotEmpty(),
                    onClick = {
                        val prev = playbackState.previousIndex()
                        if (prev >= 0) {
                            scope.launch {
                                playTrackAt(context, playbackState, prev, switchKind = TrackSwitchKind.Previous)
                            }
                        }
                    },
                )
                PlayerControlButton(
                    icon = if (playbackState.isPlaying) AppIcons.Pause else AppIcons.PlayArrow,
                    contentDescription = stringResource(
                        if (playbackState.isPlaying) R.string.home_player_pause else R.string.home_player_play
                    ),
                    enabled = playbackState.playlist.isNotEmpty(),
                    onClick = { togglePlayPause(playbackState) },
                    onSwipeUp = onPlayPauseSwipeUp,
                    // 左右滑动调音量：起手与松手同时向宿主报告进行态
                    onVolumeSwipeStart = {
                        volumeSwipe.begin()
                        currentOnVolumeAdjusting.value(true)
                    },
                    onVolumeSwipe = volumeSwipe::drag,
                    onVolumeSwipeEnd = {
                        volumeSwipe.end()
                        currentOnVolumeAdjusting.value(false)
                    },
                )
                PlayerControlButton(
                    icon = AppIcons.SkipNext,
                    contentDescription = stringResource(R.string.home_player_next),
                    enabled = playbackState.playlist.isNotEmpty(),
                    onClick = {
                        val next = playbackState.nextIndex()
                        if (next >= 0) {
                            scope.launch {
                                playTrackAt(context, playbackState, next, switchKind = TrackSwitchKind.Next)
                            }
                        }
                    },
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
        // 浮层画在控制栏上方，容器高度为 0，不影响控制栏高度与底部避让
        VolumeSwipeHud(
            state = volumeSwipe,
            modifier = Modifier.align(Alignment.TopStart),
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
    // 左右滑动调音量：位移为相对起手点的累计值（px）
    onVolumeSwipeStart: (() -> Unit)? = null,
    onVolumeSwipe: ((dragPx: Float) -> Unit)? = null,
    onVolumeSwipeEnd: (() -> Unit)? = null,
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
    if (onSwipeUp != null || onVolumeSwipe != null) {
        val density = LocalDensity.current
        // 手势协程不随回调身份重建：以最新值读取，避免重建中断进行中的手势
        val currentOnSwipeUp = rememberUpdatedState(onSwipeUp)
        val currentOnVolumeStart = rememberUpdatedState(onVolumeSwipeStart)
        val currentOnVolume = rememberUpdatedState(onVolumeSwipe)
        val currentOnVolumeEnd = rememberUpdatedState(onVolumeSwipeEnd)
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
                    detectSwipeGestures(
                        upThresholdPx = with(density) { SWIPE_UP_TRIGGER_DISTANCE.toPx() },
                        onSwipeUp = onSwipeUp?.let { { currentOnSwipeUp.value?.invoke() } },
                        onVolumeStart = onVolumeSwipeStart?.let { { currentOnVolumeStart.value?.invoke() } },
                        onVolumeDrag = onVolumeSwipe?.let { { dx: Float -> currentOnVolume.value?.invoke(dx) } },
                        onVolumeEnd = onVolumeSwipeEnd?.let { { currentOnVolumeEnd.value?.invoke() } },
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

// 按钮手势的接管方向：未定 / 横向调音量 / 纵向向上 / 放行
private enum class GestureMode { Undecided, Volume, Up, Pass }

/**
 * 控制栏按钮的滑动判定：横向主导时交给 [onVolumeDrag] 调节音量，
 * 纵向向上主导且累计超过 [upThresholdPx] 时触发 [onSwipeUp]。
 *
 * 越过触摸阈值前不消费任何事件，点击与按压反馈照常由 [combinedClickable] 处理；
 * 判定方向后才接管本次手势（消费位移与抬手）：横向拖动因此不会翻页，纵向滑动也不会
 * 同时触发切歌。纵向向下与没有对应回调的方向一律放行，交由整页切歌手势处理。
 * 上滑越过距离即唤出，不等抬手——手势已由本按钮接管，提前响应对手势距离更宽容。
 */
private suspend fun PointerInputScope.detectSwipeGestures(
    upThresholdPx: Float,
    onSwipeUp: (() -> Unit)?,
    onVolumeStart: (() -> Unit)?,
    onVolumeDrag: ((dragPx: Float) -> Unit)?,
    onVolumeEnd: (() -> Unit)?,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var accX = 0f
        var accY = 0f
        var mode = GestureMode.Undecided
        // 是否已唤出：一次手势只触发一次
        var firedUp = false
        var volumeActive = false
        try {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                // 已被上层手势接管时让出，避免同一次滑动触发两个动作
                if (change.isConsumed) break
                accX += change.positionChange().x
                accY += change.positionChange().y
                if (mode == GestureMode.Undecided) {
                    val slop = viewConfiguration.touchSlop
                    if (abs(accX) < slop && abs(accY) < slop) {
                        if (!change.pressed) break
                        continue
                    }
                    mode = when {
                        abs(accX) > abs(accY) && onVolumeDrag != null -> GestureMode.Volume
                        accY < 0f && abs(accY) > abs(accX) && onSwipeUp != null -> GestureMode.Up
                        else -> GestureMode.Pass
                    }
                    if (mode == GestureMode.Pass) break
                    if (mode == GestureMode.Volume) {
                        volumeActive = true
                        onVolumeStart?.invoke()
                    }
                }
                when (mode) {
                    GestureMode.Volume -> {
                        change.consume()
                        onVolumeDrag?.invoke(accX)
                    }
                    GestureMode.Up -> {
                        change.consume()
                        if (!firedUp && accY <= -upThresholdPx) {
                            firedUp = true
                            onSwipeUp?.invoke()
                        }
                    }
                    GestureMode.Undecided, GestureMode.Pass -> Unit
                }
                if (!change.pressed) break
            }
        } finally {
            if (volumeActive) onVolumeEnd?.invoke()
        }
    }
}

// 按钮上滑唤出弹层所需的最小上升距离：方向判定已由系统触摸阈值把关，此处取其两倍量级，
// 排除轻扫抖动，同时保证一次常规上滑即可唤出
private val SWIPE_UP_TRIGGER_DISTANCE = 32.dp
