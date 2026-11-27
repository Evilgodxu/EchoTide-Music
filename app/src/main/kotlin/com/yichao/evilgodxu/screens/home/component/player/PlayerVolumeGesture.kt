package com.yichao.evilgodxu.screens.home.component.player

import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.yichao.evilgodxu.data.music.playback.EXCLUSIVE_VOLUME_STEPS
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.ui.icons.AppIcons
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// 横拖 160dp 覆盖整个量程
private val VOLUME_SWIPE_RANGE = 160.dp
// 浮层宽上限 200dp 或可用宽 80%
private val VOLUME_OVERLAY_MAX_WIDTH = 200.dp
private const val VOLUME_OVERLAY_MAX_WIDTH_FRACTION = 0.8f
// 松手后浮层停留 600ms
private const val VOLUME_OVERLAY_LINGER_MS = 600L
// 下限 1%：部分 ROM 音量为 0 会杀进程
private const val MIN_VOLUME_FRACTION = 0.01f

/** 档位与上限，百分比同一基准 */
internal data class VolumeOverlay(val level: Int, val max: Int) {
    val percent: Int get() = if (max <= 0) 0 else (level * 100f / max).roundToInt().coerceIn(0, 100)
}

/** 音量滑动状态：起手记档位，拖动换算写回，松手停留后收起 */
@Stable
internal class VolumeSwipeState(
    private val controller: OutputVolumeController,
    private val scope: CoroutineScope,
    private val rangePx: Float,
) {
    var overlay by mutableStateOf<VolumeOverlay?>(null)
        private set

    private var startFraction = 0f
    private var maxLevel = 0
    private var hideJob: Job? = null

    fun begin() {
        hideJob?.cancel()
        val start = controller.current()
        startFraction = start.fraction
        maxLevel = start.max
        overlay = VolumeOverlay(level = start.level, max = start.max)
    }

    fun drag(dragPx: Float) {
        if (maxLevel <= 0) return
        val level = volumeLevelForDrag(
            startedFraction = startFraction,
            dragPx = dragPx,
            rangePx = rangePx,
            maxLevel = maxLevel,
        )
        controller.set(level)
        overlay = VolumeOverlay(level = level, max = maxLevel)
    }

    fun end() {
        hideJob?.cancel()
        hideJob = scope.launch {
            delay(VOLUME_OVERLAY_LINGER_MS)
            overlay = null
        }
    }
}

@Composable
internal fun rememberVolumeSwipeState(playbackState: MusicPlaybackState): VolumeSwipeState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rangePx = with(LocalDensity.current) { VOLUME_SWIPE_RANGE.toPx() }
    return remember(context, playbackState, scope, rangePx) {
        VolumeSwipeState(
            controller = OutputVolumeController(context, playbackState),
            scope = scope,
            rangePx = rangePx,
        )
    }
}

/**
 * 音量浮层：不跟手，居中贴住控制栏上缘。
 * 容器报 0 高，卡片画在上方但不参与控制栏测高。
 */
@Composable
internal fun VolumeSwipeHud(state: VolumeSwipeState, modifier: Modifier = Modifier) {
    val current = state.overlay ?: return
    val density = LocalDensity.current
    val gapPx = with(density) { 8.dp.toPx() }
    Layout(
        modifier = modifier.fillMaxWidth(),
        content = {
            VolumeOverlayCard(overlay = current)
        },
    ) { measurables, constraints ->
        val maxWidth = minOf(
            VOLUME_OVERLAY_MAX_WIDTH.roundToPx(),
            (constraints.maxWidth * VOLUME_OVERLAY_MAX_WIDTH_FRACTION).roundToInt(),
        )
        val card = measurables.first().measure(
            constraints.copy(
                minWidth = maxWidth,
                maxWidth = maxWidth,
                minHeight = 0,
                maxHeight = Constraints.Infinity,
            ),
        )
        layout(constraints.maxWidth, 0) {
            card.place(
                x = (constraints.maxWidth - card.width) / 2,
                y = -(card.height + gapPx.roundToInt()),
            )
        }
    }
}

@Composable
private fun VolumeOverlayCard(overlay: VolumeOverlay, modifier: Modifier = Modifier) {
    val percent = overlay.percent
    // 两端音量图标只作示意，不随音量变化
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = AppIcons.VolumeDown,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.8f),
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(3.dp)
                .background(Color.White.copy(alpha = 0.22f), RoundedCornerShape(1.5.dp)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(percent / 100f)
                    .fillMaxHeight()
                    .background(Color.White, RoundedCornerShape(1.5.dp)),
            )
        }
        Spacer(Modifier.width(10.dp))
        Icon(
            imageVector = AppIcons.VolumeUp,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.8f),
            modifier = Modifier.size(18.dp),
        )
    }
}

/** 目标档位：起点加位移比例，钳在 1% 量程与上限之间 */
internal fun volumeLevelForDrag(
    startedFraction: Float,
    dragPx: Float,
    rangePx: Float,
    maxLevel: Int,
): Int {
    if (maxLevel <= 0 || rangePx <= 0f) return 0
    val fraction = (startedFraction + dragPx / rangePx).coerceIn(MIN_VOLUME_FRACTION, 1f)
    return (fraction * maxLevel).roundToInt().coerceIn(minVolumeLevel(maxLevel), maxLevel)
}

/** 1% 量程的最低档位，向上取整且至少 1 档 */
internal fun minVolumeLevel(maxLevel: Int): Int =
    if (maxLevel <= 0) 0 else ceil(maxLevel * MIN_VOLUME_FRACTION).toInt().coerceIn(1, maxLevel)

/**
 * 独占时读写会话音量，判据是设备已接管。
 */
internal class OutputVolumeController(
    context: Context,
    private val playbackState: MusicPlaybackState,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)

    fun current(): OutputVolume {
        if (playbackState.usbExclusiveClaimed) {
            val step = playbackState.exclusiveVolumeStep?.invoke()
                ?: return OutputVolume(EXCLUSIVE_VOLUME_STEPS, EXCLUSIVE_VOLUME_STEPS)
            return OutputVolume(step.coerceIn(0, EXCLUSIVE_VOLUME_STEPS), EXCLUSIVE_VOLUME_STEPS)
        }
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return OutputVolume(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(0, max), max)
    }

    fun set(level: Int) {
        if (playbackState.usbExclusiveClaimed) {
            // 独占期间写应用自持档位，不落系统音量
            playbackState.setExclusiveVolumeStep?.invoke(level.coerceIn(0, EXCLUSIVE_VOLUME_STEPS))
            return
        }
        // 上限随路由变化，写入前按当前上限收口
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, level.coerceIn(0, max), 0)
    }
}

internal data class OutputVolume(val level: Int, val max: Int) {
    val fraction: Float get() = if (max <= 0) 0f else level.toFloat() / max
}
