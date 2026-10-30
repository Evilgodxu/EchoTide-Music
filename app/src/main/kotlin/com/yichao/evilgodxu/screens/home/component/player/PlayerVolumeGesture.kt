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

// 横向拖满全程所需的距离：从播放按钮起手，一次横向拖动即可覆盖整个量程
private val VOLUME_SWIPE_RANGE = 160.dp
// 浮层宽度上限：既不超过固定值，也不超过可用宽度的 80%，窄窗口下不撑满整条控制栏
private val VOLUME_OVERLAY_MAX_WIDTH = 200.dp
private const val VOLUME_OVERLAY_MAX_WIDTH_FRACTION = 0.8f
// 松手后浮层停留的时长：让最后一次取值看得清，又不压在控制栏上
private const val VOLUME_OVERLAY_LINGER_MS = 600L
// 音量下限：部分国产 ROM 在媒体音量为 0 时会清理播放进程，故调节时至少留 1% 的量程
private const val MIN_VOLUME_FRACTION = 0.01f

/** 浮层读数：档位与上限，百分比按同一基准换算 */
internal data class VolumeOverlay(val level: Int, val max: Int) {
    val percent: Int get() = if (max <= 0) 0 else (level * 100f / max).roundToInt().coerceIn(0, 100)
}

/**
 * 播放按钮左右滑动调节音量的状态：起手时记下当前档位，拖动期间按位移换算并写回，
 * 松手后让浮层短暂停留再收起。
 */
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
 * 音量浮层：不跟手，横向居中贴住控制栏上缘。
 *
 * 卡片按内容高度测量，浮层容器本身报 0 高：浮层画在控制栏上方，但不参与控制栏的高度测量，
 * 底部留白与播放模式遮罩的避让量都不因它改变，卡片高度也不会多出一段空白。
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
    // 低音量图标与高音量图标分居音量条左右两端，与条身同一条水平线；图标只作两端示意，不随音量变化
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

/**
 * 拖动位移换算成的目标档位。
 *
 * 起点档位加「位移 / 全程距离」的比例，按上限取整后钳在 [最低档位][minVolumeLevel]..[maxLevel]：
 * 左滑为负、音量降低，右滑为正、音量升高；最低档位按 1% 量程取整，避免落到 0。
 */
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

/** 1% 量程对应的最低档位：向上取整，保证不低于 1%，且至少留 1 档 */
internal fun minVolumeLevel(maxLevel: Int): Int =
    if (maxLevel <= 0) 0 else ceil(maxLevel * MIN_VOLUME_FRACTION).toInt().coerceIn(1, maxLevel)

/**
 * 输出音量的读写入口。
 *
 * 独占路由绕开系统混音器，系统媒体音量对那条流不生效；此时音量档位由应用经媒体会话的
 * 远端音量通道持有，故改为读写会话的设备音量（档位上限见 [EXCLUSIVE_VOLUME_STEPS]）。
 * 其余路径仍是系统媒体流音量，音量键与系统音量面板看到的是同一个值。
 */
internal class OutputVolumeController(
    context: Context,
    private val playbackState: MusicPlaybackState,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)

    fun current(): OutputVolume {
        if (playbackState.usbExclusiveRoute) {
            playbackState.mediaController?.let { controller ->
                val max = controller.deviceInfo.maxVolume.takeIf { it > 0 } ?: EXCLUSIVE_VOLUME_STEPS
                return OutputVolume(controller.deviceVolume.coerceIn(0, max), max)
            }
        }
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return OutputVolume(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(0, max), max)
    }

    fun set(level: Int) {
        if (playbackState.usbExclusiveRoute) {
            playbackState.mediaController?.let { controller ->
                // 带标志位的设置命令即会话远端音量的写入入口；路由切换瞬间命令尚未就绪时为空操作，
                // 不会落到系统媒体档位上——独占期间那个值不作用于当前输出
                controller.setDeviceVolume(level.coerceIn(0, EXCLUSIVE_VOLUME_STEPS), 0)
                return
            }
        }
        // 系统档位上限可能随路由变化，写入前按当前上限收口，避免越界
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, level.coerceIn(0, max), 0)
    }
}

internal data class OutputVolume(val level: Int, val max: Int) {
    val fraction: Float get() = if (max <= 0) 0f else level.toFloat() / max
}
