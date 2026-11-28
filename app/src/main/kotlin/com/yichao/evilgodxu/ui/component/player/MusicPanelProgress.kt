package com.yichao.evilgodxu.ui.component.player

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.data.music.analysis.isLosslessFormat
import com.yichao.evilgodxu.data.music.playback.AudioSignalPathFormat
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.data.music.playback.seekToAndPlay
import com.yichao.evilgodxu.ui.component.rememberVisibleSession
import com.yichao.evilgodxu.utils.formatTime
import kotlin.math.abs

@Composable
internal fun ProgressSection(
    playbackState: MusicPlaybackState,
    contentColor: Color? = null,
    onFormatClick: (() -> Unit)? = null,
) {
    // 进度与时间颜色，可被 contentColor 覆盖
    val activeColor = contentColor ?: MaterialTheme.colorScheme.primary
    val dimTextColor = contentColor ?: MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 2.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        TrackFormatInfoSection(
            playbackState = playbackState,
            contentColor = contentColor,
            onClick = onFormatClick,
            modifier = Modifier
                .fillMaxWidth()
                .alpha(0.6f),
        )
        // 时间文本与进度条共用同一动画值
        val progress by remember {
            derivedStateOf {
                if (playbackState.duration > 0) {
                    (playbackState.currentPosition.toFloat() / playbackState.duration).coerceIn(0f, 1f)
                } else 0f
            }
        }
        var seekFraction by remember { mutableFloatStateOf(progress) }
        var isSeeking by remember { mutableStateOf(false) }
        // 以曲目为动画作用域，切歌直接贴合起点
        val displayProgress = rememberAnimatedProgress(
            trackKey = playbackState.currentTrack?.id,
            targetFraction = progress,
            seekFraction = seekFraction,
            isSeeking = isSeeking,
        )
        val displayPosition = (displayProgress * playbackState.duration).toLong()

        // 时间槽位取两段文本实测宽的较大值，位数变化不抖动
        val timeStyle = LocalTextStyle.current.copy(
            fontSize = 9.sp,
            color = dimTextColor,
            // 等宽数字：同位数文本宽度一致，秒跳动不挤占末位
            fontFeatureSettings = "tnum",
        )
        val textMeasurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val durationText = formatTime(playbackState.duration)
        val positionText = formatTime(displayPosition)
        val timeSlotWidth = remember(positionText, durationText, timeStyle, textMeasurer, density) {
            // 比例数字下当前位置可能比总时长更宽，取两者较大值
            val slotWidthPx = listOf(positionText, durationText).maxOf { text ->
                textMeasurer
                    .measure(text, style = timeStyle, maxLines = 1, softWrap = false)
                    .size.width
            }
            with(density) { slotWidthPx.toDp() }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = positionText,
                style = timeStyle,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.width(timeSlotWidth),
                textAlign = TextAlign.Start
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(20.dp)
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                // 消费事件，与左右滑动互斥
                                event.changes.forEach { if (!it.isConsumed) it.consume() }
                                val pos = event.changes.first().position.x / size.width
                                seekFraction = pos.coerceIn(0f, 1f)
                                if (event.changes.any { it.pressed }) {
                                    // 按住仅显示跟手，不下发 seek 避免跳变
                                    isSeeking = true
                                } else {
                                    // 松手才跳转，单击同路径；定位后起播
                                    if (playbackState.duration > 0L) {
                                        val targetPosition = (seekFraction * playbackState.duration).toLong()
                                            .coerceIn(0L, playbackState.duration)
                                        seekToAndPlay(playbackState, targetPosition)
                                        // 立刻回写，避免被旧回报钳回
                                        playbackState.setCurrentPosition(targetPosition)
                                    }
                                    isSeeking = false
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.CenterStart
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(activeColor.copy(alpha = 0.08f), RoundedCornerShape(2.dp))
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(displayProgress)
                        .height(3.dp)
                        .background(activeColor, RoundedCornerShape(2.dp))
                )
            }
            Text(
                text = durationText,
                style = timeStyle,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.width(timeSlotWidth),
                textAlign = TextAlign.End
            )
        }
    }
}

// 音频信息条：格式、位深/采样率、比特率，可点
@Composable
internal fun TrackFormatInfoSection(
    playbackState: MusicPlaybackState,
    modifier: Modifier = Modifier,
    contentColor: Color? = null,
    onClick: (() -> Unit)? = null,
) {
    // 仅展示当前曲目当前音源的格式
    val format = playbackState.audioSignalPathFormat
        .takeIf { playbackState.isAudioSignalPathCurrent }
    val text = format?.let { formatDisplayLabel(it) }
    // 未就绪留空，保持单行高度
    Text(
        text = text.orEmpty(),
        color = contentColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 10.sp,
        maxLines = 1,
        textAlign = TextAlign.Center,
        modifier = modifier.clickable(enabled = onClick != null) { onClick?.invoke() },
    )
}

// 格式 · 位深/采样率 · 比特率，缺失项跳过
internal fun formatDisplayLabel(format: AudioSignalPathFormat): String? {
    val formatName = format.format?.removePrefix("audio/")?.takeIf { it.isNotBlank() }
    val spec = format.sampleRate?.let { rate ->
        format.bitDepth?.let { "${it}bit/${formatKhz(rate)}kHz" } ?: "${formatKhz(rate)}kHz"
    } ?: format.bitDepth?.let { "${it}bit" }
    val bitrate = format.bitrate?.let { "${it}kbps" }
    return listOfNotNull(formatName, spec, bitrate).joinToString(" · ").takeIf { it.isNotBlank() }
}

// 低于无损且可升级时返回 true
internal fun currentTrackNeedsLosslessUpgrade(playbackState: MusicPlaybackState): Boolean {
    val format = playbackState.audioSignalPathFormat
        .takeIf { playbackState.isAudioSignalPathCurrent }
        ?: return false
    if (isLosslessFormat(format)) return false
    return playbackState.currentTrack?.isUpgradableToLossless() == true
}

// 采样率转 kHz，整数不带小数
private fun formatKhz(rate: Int): String {
    val khz = rate / 1000.0
    return String.format(java.util.Locale.US, "%.1f", khz).trimEnd('0').trimEnd('.')
}

// 进度显示值：小增量直接贴合，大跳变才补间。
// 拖动中恒跟手指；以曲目与可见会话为作用域，回前台不补间。
@Composable
private fun rememberAnimatedProgress(
    trackKey: Any?,
    targetFraction: Float,
    seekFraction: Float,
    isSeeking: Boolean,
): Float {
    val target = if (isSeeking) seekFraction else targetFraction
    // 记录上一帧显示值，判定是否大跳变
    var lastDisplayed by remember(trackKey) { mutableFloatStateOf(target) }
    // 以可见会话为作用域，回前台首帧贴合
    val visibleSession = rememberVisibleSession()
    val displayed by key(visibleSession) {
        animateFloatAsState(
            targetValue = target,
            animationSpec = if (isSeeking || abs(target - lastDisplayed) <= PROGRESS_SNAP_THRESHOLD) {
                // 拖动或小增量：瞬时贴合
                tween(0)
            } else {
                tween(PROGRESS_TRANSITION_MS, easing = FastOutSlowInEasing)
            },
            label = "playerProgress",
        )
    }
    LaunchedEffect(displayed) { lastDisplayed = displayed }
    return displayed
}

// 超过曲目长度 1% 视为跳变
private const val PROGRESS_SNAP_THRESHOLD = 0.01f
// 跳变过渡时长
private const val PROGRESS_TRANSITION_MS = 600

