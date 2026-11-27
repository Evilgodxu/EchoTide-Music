package com.yichao.evilgodxu.screens.home.component.swipe

import android.content.Context
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.data.music.playback.TrackSwitchKind
import com.yichao.evilgodxu.data.music.playback.playTrackAt
import com.yichao.evilgodxu.data.settings.swipeToChangeTrackFlow
import com.yichao.evilgodxu.R
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// 取消阈值 100dp：滑回起点此距离内松手即不切歌
private val TRACK_PREVIEW_CANCEL_DISTANCE = 100.dp
// 提示最小位移 8dp，兼作方向阈值
private val TRACK_PREVIEW_MIN_DISTANCE = 8.dp
// 持续按住 200ms 才显示预览，短于此直接切歌
private const val TRACK_PREVIEW_FLICK_HOLD_MS = 200L

/** 纵向切歌：上下一首、下上一首，横向让给 Pager */
@Stable
internal class HomeTrackSwipeGesture(
    private val playbackState: MusicPlaybackState,
    private val context: Context,
    private val scope: CoroutineScope,
    private val swipeToChangeTrack: State<Boolean>,
    private val sheetVisible: State<Boolean>,
    private val nextPreviewText: State<String>,
    private val previousPreviewText: State<String>,
    private val cancelPreviewText: State<String>,
    // 取消与提示的距离阈值（px）
    private val cancelDistancePx: Float,
    private val previewMinDistancePx: Float,
) {
    // 滑动未松手时显示于封面底部的预览文本
    var previewText by mutableStateOf<String?>(null)
        private set

    // 纵向切歌手势，横向交给 Pager
    val modifier: Modifier
        get() = Modifier.pointerInput(Unit) {
            val slop = viewConfiguration.touchSlop
            awaitEachGesture {
                // 手势开始时清空预览，避免上一次手势残留
                previewText = null
                val down = awaitFirstDown(requireUnconsumed = false)
                // 累计位移过阈值后锁定方向，横向让给 Pager
                var accX = 0f
                var accY = 0f
                var horizontal = false
                var locked = false
                // 锁定时刻，作瞬间滑动判定基准
                var axisLockUptime = 0L
                while (!locked) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    // 抬手或被控件消费则放弃本手势
                    if (!change.pressed || change.isConsumed) break
                    accX += change.positionChange().x
                    accY += change.positionChange().y
                    if (abs(accX) >= slop || abs(accY) >= slop) {
                        axisLockUptime = change.uptimeMillis
                        horizontal = abs(accX) > abs(accY)
                        locked = true
                    }
                }
                if (!locked || horizontal) return@awaitEachGesture
                // 播放列表显示时让出手势，交给列表滚动
                if (sheetVisible.value) return@awaitEachGesture
                val previewEnabled = swipeToChangeTrack.value
                // 按住滑动才显示预览，滑回起点附近松手取消
                var swipeY = accY
                var maxSwipeY = abs(accY)
                var steadyHold = false
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null || !change.pressed || change.isConsumed) break
                    swipeY += change.positionChange().y
                    change.consume()
                    if (!steadyHold &&
                        change.uptimeMillis - axisLockUptime >= TRACK_PREVIEW_FLICK_HOLD_MS
                    ) {
                        steadyHold = true
                    }
                    if (abs(swipeY) > maxSwipeY) maxSwipeY = abs(swipeY)
                    if (previewEnabled && steadyHold) {
                        previewText = previewTextOf(swipeY, maxSwipeY)
                    }
                }
                // 按住滑动回起点附近取消，瞬间滑动直接切
                if (previewEnabled && (!steadyHold || abs(swipeY) >= cancelDistancePx)) {
                    switchTrack(next = swipeY < 0f)
                }
                previewText = null
            }
        }

    /** 切相邻曲目；偏好关闭或面板展开时不切 */
    fun switchTrack(next: Boolean) {
        if (!swipeToChangeTrack.value || sheetVisible.value) return
        val index = if (next) playbackState.nextIndex() else playbackState.previousIndex()
        if (index >= 0) {
            scope.launch {
                playTrackAt(
                    context,
                    playbackState,
                    index,
                    switchKind = if (next) TrackSwitchKind.Next else TrackSwitchKind.Previous,
                )
            }
        }
    }

    // 预览文本：未出取消区按方向，回落后提示取消
    private fun previewTextOf(swipeY: Float, maxSwipeY: Float): String? {
        if (maxSwipeY >= cancelDistancePx) {
            return if (abs(swipeY) < cancelDistancePx) cancelPreviewText.value
            else directionPreviewText(swipeY)
        }
        if (abs(swipeY) < previewMinDistancePx) return null
        return directionPreviewText(swipeY)
    }

    // 按方向返回预览，无目标不显示
    private fun directionPreviewText(swipeY: Float): String? = if (swipeY < 0f) {
        if (playbackState.nextIndex() >= 0) nextPreviewText.value else null
    } else {
        if (playbackState.previousIndex() >= 0) previousPreviewText.value else null
    }
}

@Composable
internal fun rememberHomeTrackSwipeGesture(
    playbackState: MusicPlaybackState,
    // 底部弹层可见时让出手势
    sheetVisible: Boolean,
): HomeTrackSwipeGesture {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 滑切歌开关，手势协程读实时值
    val swipeToChangeTrack by context.swipeToChangeTrackFlow()
        .collectAsStateWithLifecycle(initialValue = true)
    val swipeToChangeTrackState = rememberUpdatedState(swipeToChangeTrack)
    val sheetVisibleState = rememberUpdatedState(sheetVisible)
    // 提示文案随语言更新，以 State 传入
    val nextPreviewText = rememberUpdatedState(stringResource(R.string.home_player_swipe_preview_next))
    val previousPreviewText = rememberUpdatedState(stringResource(R.string.home_player_swipe_preview_previous))
    val cancelPreviewText = rememberUpdatedState(stringResource(R.string.home_player_swipe_preview_cancel))
    // 纵向切歌的距离阈值按当前密度换算为像素
    val density = LocalDensity.current
    val cancelDistancePx = with(density) { TRACK_PREVIEW_CANCEL_DISTANCE.toPx() }
    val previewMinDistancePx = with(density) { TRACK_PREVIEW_MIN_DISTANCE.toPx() }
    return remember(playbackState, context, cancelDistancePx, previewMinDistancePx) {
        HomeTrackSwipeGesture(
            playbackState = playbackState,
            context = context,
            scope = scope,
            swipeToChangeTrack = swipeToChangeTrackState,
            sheetVisible = sheetVisibleState,
            nextPreviewText = nextPreviewText,
            previousPreviewText = previousPreviewText,
            cancelPreviewText = cancelPreviewText,
            cancelDistancePx = cancelDistancePx,
            previewMinDistancePx = previewMinDistancePx,
        )
    }
}
