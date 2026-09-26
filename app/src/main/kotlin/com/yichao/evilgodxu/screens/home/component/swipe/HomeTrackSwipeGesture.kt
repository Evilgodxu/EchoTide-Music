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
import com.yichao.evilgodxu.data.music.playback.playTrackAt
import com.yichao.evilgodxu.data.settings.swipeToChangeTrackFlow
import com.yichao.evilgodxu.R
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// 纵向切换曲目：滑动（未松手）期间在封面底部显示将播放的曲目方向；
// 松手时位移回到起点该距离内判定为取消切歌，否则按方向切换
private val TRACK_PREVIEW_CANCEL_DISTANCE = 100.dp
// 提示显示所需的最小位移，兼作滑动的方向判定阈值
private val TRACK_PREVIEW_MIN_DISTANCE = 8.dp
// 滑动开始后持续按住该时长才视为按住滑动（显示预览）；短于此视为瞬间滑动，直接切歌不提示
private const val TRACK_PREVIEW_FLICK_HOLD_MS = 200L

/**
 * 首页纵向切歌手势：向上切下一曲、向下切上一曲，只在播放器页生效。
 *
 * 横向翻页由 Pager 承担：本手势在纵向主导时消费事件，横向主导时直接让出，两者互斥。
 */
@Stable
internal class HomeTrackSwipeGesture(
    private val playbackState: MusicPlaybackState,
    private val context: Context,
    private val scope: CoroutineScope,
    private val swipeToChangeTrack: State<Boolean>,
    private val playlistSheetVisible: State<Boolean>,
    private val nextPreviewText: State<String>,
    private val previousPreviewText: State<String>,
    private val cancelPreviewText: State<String>,
    // 纵向切歌滑回取消与提示显示的距离阈值（px）
    private val cancelDistancePx: Float,
    private val previewMinDistancePx: Float,
) {
    // 滑动未松手时显示于封面底部的预览文本
    var previewText by mutableStateOf<String?>(null)
        private set

    // 纵向切歌手势（横向已交由 Pager，本手势不再参与左右翻页）
    val modifier: Modifier
        get() = Modifier.pointerInput(Unit) {
            val slop = viewConfiguration.touchSlop
            awaitEachGesture {
                // 手势开始时清空预览，避免上一次手势残留
                previewText = null
                val down = awaitFirstDown(requireUnconsumed = false)
                // 方向锁定：累计位移直到任一轴越过触摸阈值，横向主导则让出给 Pager
                var accX = 0f
                var accY = 0f
                var horizontal = false
                var locked = false
                // 纵向滑动锁定时刻：作为瞬间滑动(一甩即松手)的判定基准
                var axisLockUptime = 0L
                while (!locked) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    // 手指抬起或被子控件（进度条/控制栏等）消费，放弃本手势
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
                // 首页播放列表显示期间让出纵向手势：不消费事件也不切歌，滚动交由播放列表处理
                if (playlistSheetVisible.value) return@awaitEachGesture
                val previewEnabled = swipeToChangeTrack.value
                // 滑动开始后持续按住（未松手）才实时显示将播放的曲目方向，滑回起点附近松手取消切歌；
                // 瞬间滑动（一甩即松手）保持原逻辑直接切歌，不显示提示
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
                // 松手判定：按住滑动时位移回到起点附近则取消切歌；瞬间滑动保持原逻辑直接切歌
                if (previewEnabled && (!steadyHold || abs(swipeY) >= cancelDistancePx)) {
                    switchTrack(next = swipeY < 0f)
                }
                previewText = null
            }
        }

    /**
     * 切到相邻曲目：纵向切歌手势松手与歌词区快速滑动共用同一出口，
     * 偏好关闭或播放列表弹层展开时不切歌。
     */
    fun switchTrack(next: Boolean) {
        if (!swipeToChangeTrack.value || playlistSheetVisible.value) return
        val index = if (next) playbackState.nextIndex() else playbackState.previousIndex()
        if (index >= 0) scope.launch { playTrackAt(context, playbackState, index) }
    }

    // 纵向切歌预览文本：未滑出过取消区时按方向预览（极小位移不显示）；
    // 滑出过取消区后回落到取消区内统一提示松手取消，接近起点也保持显示
    private fun previewTextOf(swipeY: Float, maxSwipeY: Float): String? {
        if (maxSwipeY >= cancelDistancePx) {
            return if (abs(swipeY) < cancelDistancePx) cancelPreviewText.value
            else directionPreviewText(swipeY)
        }
        if (abs(swipeY) < previewMinDistancePx) return null
        return directionPreviewText(swipeY)
    }

    // 按当前滑动方向返回将播放的曲目提示，目标不存在时不显示
    private fun directionPreviewText(swipeY: Float): String? = if (swipeY < 0f) {
        if (playbackState.nextIndex() >= 0) nextPreviewText.value else null
    } else {
        if (playbackState.previousIndex() >= 0) previousPreviewText.value else null
    }
}

@Composable
internal fun rememberHomeTrackSwipeGesture(
    playbackState: MusicPlaybackState,
    // 播放列表弹层可见时让出纵向手势
    playlistSheetVisible: Boolean,
): HomeTrackSwipeGesture {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 播放偏好：滑动切歌开关；手势协程中读取实时值，避免捕获过期状态
    val swipeToChangeTrack by context.swipeToChangeTrackFlow()
        .collectAsStateWithLifecycle(initialValue = true)
    val swipeToChangeTrackState = rememberUpdatedState(swipeToChangeTrack)
    val playlistSheetVisibleState = rememberUpdatedState(playlistSheetVisible)
    // 提示文案随语言切换更新，同样以 State 形式供手势协程读取
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
            playlistSheetVisible = playlistSheetVisibleState,
            nextPreviewText = nextPreviewText,
            previousPreviewText = previousPreviewText,
            cancelPreviewText = cancelPreviewText,
            cancelDistancePx = cancelDistancePx,
            previewMinDistancePx = previewMinDistancePx,
        )
    }
}
