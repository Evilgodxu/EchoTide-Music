package com.yichao.evilgodxu.floatingwindow.miniplayer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.data.music.model.PlayMode
import com.yichao.evilgodxu.data.settings.wordByWordRenderingFlow
import com.yichao.evilgodxu.data.music.playback.applyPlaybackMode
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.data.music.playback.playTrackAt
import com.yichao.evilgodxu.data.music.playback.togglePlayPause
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.ui.icons.AppIcons
import com.yichao.evilgodxu.ui.component.player.DiscArt
import kotlin.math.min
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
internal fun MiniPlayerBar(
    playbackState: MusicPlaybackState,
    barHeight: Dp,
    playlistExpanded: Boolean,
    onPlaylistExpandedChange: (Boolean) -> Unit,
    onExpandPanel: () -> Unit,
    swipeTrackThreshold: Float,
    onSwipeOffsetChange: (Float) -> Unit,
    onSwipeCancel: () -> Unit,
    onSwipeDown: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // 逐字渲染开关：关闭后整行高亮，不再逐字点亮
    val wordByWordEnabled by context.wordByWordRenderingFlow().collectAsState(initial = true)
    val current = playbackState.currentTrack
    val coverDesc = stringResource(R.string.mini_player_cover)

    // 控件自动隐藏：3 秒无操作后隐藏控制按钮，改为显示歌曲名与歌词；任意触摸即可还原
    var controlsVisible by remember { mutableStateOf(true) }
    var interactionTick by remember { mutableIntStateOf(0) }
    fun resetAutoHide() {
        controlsVisible = true
        interactionTick++
    }
    LaunchedEffect(interactionTick, playlistExpanded) {
        if (playlistExpanded) {
            controlsVisible = true
            return@LaunchedEffect
        }
        delay(3000)
        controlsVisible = false
    }
    // 隐藏控件期间跟随播放进度刷新当前歌词；跟随当前曲目，切换歌曲时重置到曲目起点
    var lyricPosition by remember(playbackState.currentTrack?.id) { mutableLongStateOf(0L) }
    LaunchedEffect(controlsVisible, playbackState.currentTrack?.id) {
        if (controlsVisible) return@LaunchedEffect
        var lastSyncMs = 0L
        while (isActive) {
            val candidate = playbackState.mediaController?.currentPosition
                ?.takeIf { it >= 0L } ?: playbackState.currentPosition
            if (playbackState.isPlaying) {
                val now = System.currentTimeMillis()
                val elapsed = if (lastSyncMs == 0L) 0L else (now - lastSyncMs).coerceAtLeast(0L)
                // 位置大幅回退视为单曲循环回卷/手动拖动：直接锚定到控制器位置，
                // 避免回卷后按流逝时间继续递增，导致歌词定格在末行不再更新
                lyricPosition = when {
                    candidate >= lyricPosition -> candidate
                    lyricPosition - candidate > MINI_LYRIC_SEEK_TOLERANCE_MS -> candidate
                    else -> lyricPosition + elapsed
                }
                lastSyncMs = now
            } else {
                // 暂停时保持本地已推进的真实位置，仅跟随控制器前移（正向 seek）或大幅
                // 回退（手动拖动），避免控制器滞后回报把已唱完的歌词高亮拉回
                lyricPosition = when {
                    candidate >= lyricPosition -> candidate
                    lyricPosition - candidate > MINI_LYRIC_SEEK_TOLERANCE_MS -> candidate
                    else -> lyricPosition
                }
                lastSyncMs = 0L
            }
            delay(if (playbackState.isPlaying) 50L else 200L)
        }
    }

    // 手势交互：左右滑动切歌（右滑上一曲、左滑下一曲）；下滑隐藏播放器
    val verticalSwipeThresholdPx = with(LocalDensity.current) { MINI_SWIPE_VERTICAL_THRESHOLD_DP.dp.toPx() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(barHeight)
            // 手势按首个越过触摸阈值的轴向锁定：横滑切歌与下滑隐藏互斥，斜滑不会同时触发；
            // 仅未越过阈值的轻点还原控制栏，滑动切歌/下滑隐藏属于滑动手势，不触发控制栏显隐
            .pointerInput(playlistExpanded) {
                var totalDx = 0f
                var totalDy = 0f
                awaitEachGesture {
                    // 0=未锁定, 1=水平(切歌), 2=垂直(下滑隐藏)
                    var axis = 0
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val drag = awaitTouchSlopOrCancellation(down.id) { change, over ->
                        axis = if (kotlin.math.abs(over.x) > kotlin.math.abs(over.y)) 1 else 2
                        change.consume()
                    }
                    if (drag == null) {
                        // 未越过触摸阈值（含点击控制按钮被消费）：视为轻点，还原控制栏并顺延自动隐藏计时
                        if (!playlistExpanded) resetAutoHide()
                        return@awaitEachGesture
                    }
                    drag.consume()
                    // 只累计锁定轴向的位移，直到抬手或手势被取消
                    var gestureEnded = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val delta = change.position - change.previousPosition
                        if (axis == 1) {
                            totalDx += delta.x
                            onSwipeOffsetChange(totalDx)
                        } else {
                            totalDy += delta.y
                        }
                        change.consume()
                        if (!change.pressed) {
                            // 仅手指正常抬起才执行手势操作，手势被系统取消时不触发
                            gestureEnded = event.type == PointerEventType.Release
                            break
                        }
                    }
                    if (gestureEnded && !playlistExpanded) {
                        when (axis) {
                            // 水平：右滑上一曲、左滑下一曲；直接切歌，无需滑出动画
                            1 -> if (kotlin.math.abs(totalDx) >= swipeTrackThreshold) {
                                val index = if (totalDx > 0f) {
                                    playbackState.previousIndex()
                                } else {
                                    playbackState.nextIndex()
                                }
                                if (index >= 0) scope.launch { playTrackAt(context, playbackState, index) }
                            }
                            // 垂直：下滑隐藏播放器
                            2 -> if (totalDy > verticalSwipeThresholdPx) onSwipeDown()
                        }
                    }
                    onSwipeCancel()
                    totalDx = 0f
                    totalDy = 0f
                }
            }
            .padding(horizontal = MINI_PADDING_H_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(0.dp, Alignment.CenterHorizontally)
    ) {
        // 专辑封面：旋转 + 黑胶质感
        Box(
            modifier = Modifier
                .size(MINI_COVER_DP.dp)
                .clip(CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onExpandPanel
                ),
            contentAlignment = Alignment.Center,
        ) {
            DiscArt(
                track = current,
                isPlaying = playbackState.isPlaying,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (controlsVisible) {
            // 循环模式
            MiniControlButton(
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
                }
            )
            // 上一曲
            MiniControlButton(
                icon = AppIcons.SkipPrevious,
                contentDescription = stringResource(R.string.mini_player_previous),
                enabled = playbackState.playlist.isNotEmpty(),
                onClick = {
                    val prev = playbackState.previousIndex()
                    if (prev >= 0) scope.launch { playTrackAt(context, playbackState, prev) }
                }
            )
            // 暂停 / 播放
            MiniControlButton(
                icon = if (playbackState.isPlaying) AppIcons.Pause else AppIcons.PlayArrow,
                contentDescription = stringResource(
                    if (playbackState.isPlaying) R.string.music_panel_pause else R.string.music_panel_play
                ),
                onClick = { togglePlayPause(playbackState) }
            )
            // 下一曲
            MiniControlButton(
                icon = AppIcons.SkipNext,
                contentDescription = stringResource(R.string.mini_player_next),
                enabled = playbackState.playlist.isNotEmpty(),
                onClick = {
                    val next = playbackState.nextIndex()
                    if (next >= 0) scope.launch { playTrackAt(context, playbackState, next) }
                }
            )
            // 播放列表
            MiniControlButton(
                icon = AppIcons.QueueMusic,
                contentDescription = stringResource(R.string.mini_player_playlist),
                onClick = { onPlaylistExpandedChange(!playlistExpanded) }
            )
        } else {
            // 隐藏控件：展示歌曲名与当前歌词
            val lyricLines = current?.lyricLines.orEmpty()
            val lyricIndex = if (lyricLines.isNotEmpty()) {
                lyricLines.indexOfLast { it.timeMs <= lyricPosition }.coerceAtLeast(0)
            } else -1
            val lyricLine = lyricLines.getOrNull(lyricIndex)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.Center
            ) {
                MiniPlayerMarqueeText(
                    text = current?.title.orEmpty(),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = TextStyle(
                        fontSize = 12.sp,
                        lineHeight = 15.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
                if (lyricLine == null) {
                    // 无歌词时退化为歌手名静态展示
                    Text(
                        text = current?.artist.orEmpty(),
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.Medium,
                    )
                } else {
                    // 行内演唱进度：按本行到下一行的起止时间折算；填充时长略短于行时长，
                    // 留出余量让行尾文字在切到下一行前完整揭示
                    val lineEndMs = lyricLines
                        .getOrNull(lyricIndex + 1)
                        ?.timeMs ?: (lyricLine.timeMs + 3000L)
                    val lineDuration = (lineEndMs - lyricLine.timeMs).coerceAtLeast(1L)
                    val fillDuration = lineDuration - min(400L, lineDuration / 5)
                    val progress = ((lyricPosition - lyricLine.timeMs).toFloat() /
                        fillDuration.toFloat())
                        .coerceIn(0f, 1f)
                    MiniPlayerLyricText(
                        text = lyricLine.text,
                        progress = progress,
                        wordByWordEnabled = wordByWordEnabled,
                        activeColor = MaterialTheme.colorScheme.primary,
                        pendingColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                        style = TextStyle(
                            fontSize = 10.sp,
                            lineHeight = 12.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        lineKey = current?.id to lyricIndex,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

// 迷你条歌词行：整行歌词常显，演唱进度从左向右点亮高亮；文字超宽时跟随点亮边缘平移，
// 溢出部分随亮起自然滚入视野。关闭逐字渲染时整行高亮，仅保留跟随进度的平移滚动
@Composable
private fun MiniPlayerLyricText(
    text: String,
    progress: Float,
    wordByWordEnabled: Boolean,
    activeColor: Color,
    pendingColor: Color,
    style: TextStyle,
    lineKey: Any,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val layout = remember(text, style) { textMeasurer.measure(AnnotatedString(text), style) }
    // 点亮进度按行小幅平滑推进；换行时重置归零，避免上一行进度回卷的闪烁
    val revealProgress = remember(lineKey) { Animatable(progress) }
    LaunchedEffect(lineKey, progress) {
        revealProgress.animateTo(
            targetValue = progress,
            animationSpec = tween(durationMillis = MINI_LYRIC_REVEAL_SMOOTH_MS, easing = LinearEasing),
        )
    }
    Box(
        modifier = modifier
            .height(with(density) { layout.size.height.toDp() })
            .drawWithContent {
                val textWidth = layout.size.width.toFloat()
                if (textWidth <= 0f || size.width <= 0f) return@drawWithContent
                val progressValue = revealProgress.value.coerceIn(0f, 1f)
                // 点亮边缘：逐字模式随进度推进，整行模式恒为全宽
                val revealEdge = if (wordByWordEnabled) progressValue * textWidth else textWidth
                // 文字宽于视口时向左平移，让唱到的字始终可见
                val translateX = min(0f, size.width - progressValue * textWidth)
                if (wordByWordEnabled) {
                    // 待唱层：整行以暗色常显
                    clipRect(left = 0f, right = size.width) {
                        drawText(layout, color = pendingColor, topLeft = Offset(translateX, 0f))
                    }
                    // 点亮层：边缘左侧整字高亮，右侧保持待唱色
                    clipRect(left = 0f, right = translateX + revealEdge) {
                        drawText(layout, color = activeColor, topLeft = Offset(translateX, 0f))
                    }
                } else {
                    // 整行统一高亮，仍在视口内随进度滚动平移
                    clipRect(left = 0f, right = size.width) {
                        drawText(layout, color = activeColor, topLeft = Offset(translateX, 0f))
                    }
                }
            }
    )
}

// 迷你条歌曲名：标题超宽时以跑马灯滚动展示，避免硬截断；未溢出时静态左对齐
@Composable
private fun MiniPlayerMarqueeText(
    text: String,
    color: Color,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val layout = remember(text, style) { textMeasurer.measure(AnnotatedString(text), style) }
    var containerWidthPx by remember { mutableFloatStateOf(0f) }
    val maxScroll = (layout.size.width - containerWidthPx).coerceAtLeast(0f)
    val offset = remember(text) { Animatable(0f) }
    LaunchedEffect(text, maxScroll) {
        if (containerWidthPx <= 0f || maxScroll <= 0f) {
            offset.snapTo(0f)
            return@LaunchedEffect
        }
        // 按固定速度折算滚动时长，保证不同长度标题速度一致
        val speedPxPerMs = with(density) { MINI_TITLE_MARQUEE_SPEED.toPx() } / 1000f
        val scrollMs = (maxScroll / speedPxPerMs).toInt().coerceAtLeast(1)
        while (isActive) {
            delay(MINI_TITLE_MARQUEE_PAUSE_MS)
            offset.animateTo(maxScroll, tween(scrollMs, easing = LinearEasing))
            delay(MINI_TITLE_MARQUEE_PAUSE_MS)
            offset.animateTo(0f, tween(scrollMs, easing = LinearEasing))
        }
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(with(density) { layout.size.height.toDp() })
            .onSizeChanged { containerWidthPx = it.width.toFloat() }
            .drawWithContent {
                if (layout.size.width <= 0f || size.width <= 0f) return@drawWithContent
                val translateX = if (layout.size.width <= size.width) 0f else -offset.value
                clipRect(left = 0f, top = 0f, right = size.width, bottom = size.height) {
                    drawText(layout, color = color, topLeft = Offset(translateX, 0f))
                }
            }
            .semantics { contentDescription = text }
    )
}

// 迷你条控制按钮：视觉圆环小于触控热区，避免在紧凑高度下贴满上下边缘
@Composable
private fun MiniControlButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(MINI_BUTTON_DP.dp)
    ) {
        Box(
            modifier = Modifier.size(28.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(20.dp),
                tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
            )
        }
    }
}

// 歌词揭示平滑时长：行内推进无明显跳变
private const val MINI_LYRIC_REVEAL_SMOOTH_MS = 60
// 歌词位置回退容差：超过该值视为单曲循环回卷/手动拖动，直接锚定控制器位置
private const val MINI_LYRIC_SEEK_TOLERANCE_MS = 1500L
// 跑马灯滚动速度：长标题按此速度匀速平移
private val MINI_TITLE_MARQUEE_SPEED = 24.dp
// 跑马灯两端停顿时长：滚动前/后短暂停留便于阅读
private const val MINI_TITLE_MARQUEE_PAUSE_MS = 1200L