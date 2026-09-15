package com.yichao.evilgodxu.ui.component.player

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yichao.evilgodxu.data.music.analysis.isLosslessFormat
import com.yichao.evilgodxu.data.music.playback.AudioSignalPathFormat
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.data.music.playback.seekTo
import com.yichao.evilgodxu.utils.formatTime
import kotlin.math.abs

@Composable
internal fun ProgressSection(
    playbackState: MusicPlaybackState,
    contentColor: Color? = null,
    onFormatClick: (() -> Unit)? = null,
) {
    // 进度条与时间文本颜色：默认取主题色，传入 contentColor 时（如首页）覆盖为指定色
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
        // 进度显示值先于 Row 计算：左侧时间文本与进度条共用同一动画值，位置跳变时平滑联动
        val progress by remember {
            derivedStateOf {
                if (playbackState.duration > 0) {
                    (playbackState.currentPosition.toFloat() / playbackState.duration).coerceIn(0f, 1f)
                } else 0f
            }
        }
        var seekFraction by remember { mutableFloatStateOf(progress) }
        var isSeeking by remember { mutableStateOf(false) }
        // 以当前曲目为动画作用域：切歌时上一曲显示基准随 trackKey 一并重建，直接贴合新曲起点
        val displayProgress = rememberAnimatedProgress(
            trackKey = playbackState.currentTrack?.id,
            targetFraction = progress,
            seekFraction = seekFraction,
            isSeeking = isSeeking,
        )
        val displayPosition = (displayProgress * playbackState.duration).toLong()

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = formatTime(displayPosition),
                color = dimTextColor,
                fontSize = 9.sp,
                modifier = Modifier.width(24.dp),
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
                                // 消耗进度条上的指针事件，与全局左右滑动互斥，拖动进度条时不触发切换面板
                                event.changes.forEach { if (!it.isConsumed) it.consume() }
                                val pos = event.changes.first().position.x / size.width
                                seekFraction = pos.coerceIn(0f, 1f)
                                isSeeking = true
                                if (event.changes.first().pressed) {
                                    seekTo(playbackState, (seekFraction * playbackState.duration).toLong())
                                    playbackState.setCurrentPosition((seekFraction * playbackState.duration).toLong().coerceIn(0L, playbackState.duration))
                                }
                                if (event.changes.all { !it.pressed }) {
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
                text = formatTime(playbackState.duration),
                color = dimTextColor,
                fontSize = 9.sp,
                modifier = Modifier.width(24.dp),
                textAlign = TextAlign.End
            )
        }
    }
}

// 竖向进度条：复用横向进度条样式（圆角轨道 + 主色填充），不带时间文本
@Composable
internal fun VerticalProgressBar(
    playbackState: MusicPlaybackState,
    modifier: Modifier = Modifier,
    contentColor: Color? = null,
) {
    val activeColor = contentColor ?: MaterialTheme.colorScheme.primary
    val progress by remember {
        derivedStateOf {
            if (playbackState.duration > 0) {
                (playbackState.currentPosition.toFloat() / playbackState.duration).coerceIn(0f, 1f)
            } else 0f
        }
    }
    var seekFraction by remember { mutableFloatStateOf(progress) }
    var isSeeking by remember { mutableStateOf(false) }
    // 以当前曲目为动画作用域：切歌时上一曲显示基准随 trackKey 一并重建，直接贴合新曲起点
    val displayProgress = rememberAnimatedProgress(
        trackKey = playbackState.currentTrack?.id,
        targetFraction = progress,
        seekFraction = seekFraction,
        isSeeking = isSeeking,
    )

    Box(
        modifier = modifier
            .width(20.dp)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        // 消耗进度条上的指针事件，与全局左右滑动互斥，拖动进度条时不触发左右切换面板
                        event.changes.forEach { if (!it.isConsumed) it.consume() }
                        val pos = event.changes.first().position.y / size.height
                        seekFraction = (1f - pos).coerceIn(0f, 1f)
                        isSeeking = true
                        if (event.changes.first().pressed) {
                            seekTo(playbackState, (seekFraction * playbackState.duration).toLong())
                            playbackState.setCurrentPosition(
                                (seekFraction * playbackState.duration).toLong().coerceIn(0L, playbackState.duration)
                            )
                        }
                        if (event.changes.all { !it.pressed }) {
                            isSeeking = false
                        }
                    }
                }
            },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(3.dp)
                .background(activeColor.copy(alpha = 0.08f), RoundedCornerShape(2.dp))
        )
        Box(
            modifier = Modifier
                .fillMaxHeight(displayProgress)
                .width(3.dp)
                .background(activeColor, RoundedCornerShape(2.dp))
        )
    }
}

// 音频信息条：展示当前曲目格式、位深/采样率与比特率，信息未就绪时留空；
// 传入 onClick 时整条可点击（首页用于触发无损升级）
@Composable
internal fun TrackFormatInfoSection(
    playbackState: MusicPlaybackState,
    modifier: Modifier = Modifier,
    contentColor: Color? = null,
    onClick: (() -> Unit)? = null,
) {
    // 仅展示属于当前曲目当前音频源的格式信息，避免后台切歌或升级换源后错配残留
    val format = playbackState.audioSignalPathFormat
        .takeIf { playbackState.isAudioSignalPathCurrent }
    val text = format?.let { formatDisplayLabel(it) }
    // 信息未就绪时渲染空文本，占位保持单行高度，避免底部控制栏随信息条显隐而跳变
    Text(
        text = text.orEmpty(),
        color = contentColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 10.sp,
        maxLines = 1,
        textAlign = TextAlign.Center,
        modifier = modifier.clickable(enabled = onClick != null) { onClick?.invoke() },
    )
}

// 格式信息展示文本：格式 · 位深/采样率 · 比特率；无有效信息时返回 null
internal fun formatDisplayLabel(format: AudioSignalPathFormat): String? {
    if (format.sampleRate <= 0 && format.bitrate <= 0) return null
    val formatName = format.format.removePrefix("audio/")
    val bitRate = if (format.sampleRate > 0) {
        "${format.bitDepth}bit/${formatKhz(format.sampleRate)}kHz"
    } else "${format.bitDepth}bit"
    val bitrate = format.bitrate.takeIf { it > 0 }?.let { "${it}kbps" }
    return listOfNotNull(formatName, bitRate, bitrate).joinToString(" · ")
}

// 当前曲目是否触发无损升级：展示格式低于无损且该曲目可升级
internal fun currentTrackNeedsLosslessUpgrade(playbackState: MusicPlaybackState): Boolean {
    val format = playbackState.audioSignalPathFormat
        .takeIf { playbackState.isAudioSignalPathCurrent }
        ?: return false
    if (isLosslessFormat(format)) return false
    return playbackState.currentTrack?.isUpgradableToLossless() == true
}

// 采样率转 kHz 文本：整数值不带小数点，非整数值保留一位小数
private fun formatKhz(rate: Int): String {
    val khz = rate / 1000.0
    return String.format(java.util.Locale.US, "%.1f", khz).trimEnd('0').trimEnd('.')
}

// 进度条显示值：正常播放的逐帧小增量直接贴合真实进度，仅当位置大幅跳变时（冷启动还原、
// 手动拖动定位、切歌重载）以过渡动画平滑到达，避免进度条突兀跳动。拖动中恒跟随手指不插值。
// 进度以 [trackKey]（当前曲目）为作用域：切歌时旧曲显示基准随之重建，使新曲进度
// 直接贴合到起点，避免从旧曲中途位置回退到 0 的冗余动画。
// 回前台不属跳变：后台期间进度照常推进，窗口重新可见时直接显示当前进度，不做补间。
@Composable
private fun rememberAnimatedProgress(
    trackKey: Any?,
    targetFraction: Float,
    seekFraction: Float,
    isSeeking: Boolean,
): Float {
    val target = if (isSeeking) seekFraction else targetFraction
    // 记录上一帧显示值，用于判定本次变化是否为需动画的大跳变
    var lastDisplayed by remember(trackKey) { mutableFloatStateOf(target) }
    // 以可见会话为动画作用域：窗口不可见期间合成与动画时钟停摆，显示值滞留在切后台那一刻，
    // 会话重建使动画以当前进度为初值，回前台首帧即贴合真实进度，而非把后台累计的增量
    // 当作跳变补间一次（观感上是一次追溯式快进）
    val visibleSession = rememberVisibleSession()
    val displayed by key(visibleSession) {
        animateFloatAsState(
            targetValue = target,
            animationSpec = if (isSeeking || abs(target - lastDisplayed) <= PROGRESS_SNAP_THRESHOLD) {
                // 拖动中或小增量：瞬时贴合，不引入视觉滞后
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

// 窗口可见会话序号：窗口不可见时合成与动画随窗口一并停摆，进度显示值停留在切后台那一刻。
// 每次重新可见递增序号，供进度动画据此重建状态，直接贴合回前台时的实际进度。
@Composable
private fun rememberVisibleSession(): Int {
    val lifecycleOwner = LocalLifecycleOwner.current
    var session by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) session++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return session
}

// 进度大幅跳变的判定阈值：超过曲目长度的该比例视为跳变需动画过渡，否则直接贴合
private const val PROGRESS_SNAP_THRESHOLD = 0.01f
// 进度跳变过渡时长
private const val PROGRESS_TRANSITION_MS = 600

