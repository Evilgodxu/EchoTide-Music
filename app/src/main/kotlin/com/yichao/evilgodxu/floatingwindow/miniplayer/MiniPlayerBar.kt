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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.data.settings.wordByWordRenderingFlow
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.data.music.playback.TrackSwitchKind
import com.yichao.evilgodxu.data.music.playback.playTrackAt
import com.yichao.evilgodxu.data.music.playback.togglePlayPause
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.ui.icons.AppIcons
import com.yichao.evilgodxu.ui.component.MarqueeText
import com.yichao.evilgodxu.ui.component.playModeMenuOrder
import com.yichao.evilgodxu.ui.component.PlayModeOptionButton
import com.yichao.evilgodxu.ui.component.PlayModeToggleButton
import com.yichao.evilgodxu.ui.component.selectPlayMode
import com.yichao.evilgodxu.ui.component.TrackSwitchStyle
import com.yichao.evilgodxu.ui.component.TrackSwitchTransition
import com.yichao.evilgodxu.ui.component.rememberTrackSwitchAnchor
import com.yichao.evilgodxu.ui.component.player.DiscArt
import com.yichao.evilgodxu.ui.component.player.lyricEndLeadMs
import com.yichao.evilgodxu.ui.component.player.lyricWordEnds
import com.yichao.evilgodxu.ui.component.player.lyricWordFillFraction
import kotlin.math.min
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
internal fun MiniPlayerBar(
    playbackState: MusicPlaybackState,
    barHeight: Dp,
    playlistExpanded: Boolean,
    onTogglePlaylist: () -> Unit,
    onOpenApp: () -> Unit,
    swipeTrackThreshold: Float,
    onSwipeOffsetChange: (Float) -> Unit,
    onSwipeCancel: () -> Unit,
    onSwipeDown: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // 关闭后整行高亮
    val wordByWordEnabled by context.wordByWordRenderingFlow().collectAsState(initial = true)
    val current = playbackState.currentTrack
    val coverDesc = stringResource(R.string.mini_player_cover)
    // 切歌锚点：歌名与歌词整体淡入淡出
    val trackAnchor = rememberTrackSwitchAnchor(current, playbackState.lastSwitchKind)

    // 3 秒无操作隐藏控件，改显歌名与歌词
    var controlsVisible by remember { mutableStateOf(true) }
    var interactionTick by remember { mutableIntStateOf(0) }
    // 播放模式展开态：控制按钮让位给模式选项
    var playModeSelectorExpanded by remember { mutableStateOf(false) }
    fun resetAutoHide() {
        controlsVisible = true
        interactionTick++
    }
    LaunchedEffect(interactionTick, playModeSelectorExpanded, playlistExpanded) {
        // 展开期间不自动隐藏，避免只剩点外部收起
        if (playModeSelectorExpanded || playlistExpanded) {
            controlsVisible = true
            return@LaunchedEffect
        }
        delay(3000)
        controlsVisible = false
    }
    // 歌词进度跟随当前曲目，切曲重置；起步取状态层时间轴
    var lyricPosition by remember(playbackState.currentTrack?.id) {
        mutableLongStateOf(playbackState.seedLyricPosition(playbackState.currentTrack?.id))
    }
    // 跟踪随曲目常驻，避免隐藏期进度落后
    LaunchedEffect(playbackState.currentTrack?.id) {
        // 本次跟随所属曲目，切曲不沿用
        val trackId = playbackState.currentTrack?.id
        var lastSyncMs = 0L
        while (isActive) {
            // 锚点取即时位置，已换算为整曲绝对时间
            val candidate = playbackState.lyricPositionMs
            val now = System.currentTimeMillis()
            if (playbackState.isPlaying) {
                val elapsed = if (lastSyncMs == 0L) 0L else (now - lastSyncMs).coerceAtLeast(0L)
                // 大幅回退视为回卷或拖动，直接锚定
                lyricPosition = when {
                    candidate >= lyricPosition -> candidate
                    lyricPosition - candidate > MINI_LYRIC_SEEK_TOLERANCE_MS -> candidate
                    else -> lyricPosition + elapsed
                }
                lastSyncMs = now
            } else {
                // 暂停时保留本地进度，只跟随前移或大幅回退
                lyricPosition = when {
                    candidate >= lyricPosition -> candidate
                    lyricPosition - candidate > MINI_LYRIC_SEEK_TOLERANCE_MS -> candidate
                    else -> lyricPosition
                }
                lastSyncMs = 0L
            }
            // 回写进度，供首页重建接续
            playbackState.recordLyricPosition(trackId, lyricPosition)
            delay(if (playbackState.isPlaying) 50L else 200L)
        }
    }

    // 左右滑切歌，下滑隐藏
    val verticalSwipeThresholdPx = with(LocalDensity.current) { MINI_SWIPE_VERTICAL_THRESHOLD_DP.dp.toPx() }

    // 展开时才挂的兜底点击：点空隙收起选择
    val dismissSelectorModifier = if (playModeSelectorExpanded) {
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
        ) {
            playModeSelectorExpanded = false
            resetAutoHide()
        }
    } else {
        Modifier
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(barHeight)
            // 按首个越过阈值的轴向锁定；轻点才还原控制栏
            .pointerInput(Unit) {
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
                        // 未越过阈值视为轻点，还原控制栏
                        resetAutoHide()
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
                            // 仅正常抬起才执行，取消时不触发
                            gestureEnded = event.type == PointerEventType.Release
                            break
                        }
                    }
                    if (gestureEnded) {
                        when (axis) {
                            // 右滑上一曲、左滑下一曲
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
            .then(dismissSelectorModifier)
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
                    // 展开时点封面只收起选择
                    onClick = {
                        if (playModeSelectorExpanded) playModeSelectorExpanded = false else onOpenApp()
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            DiscArt(
                track = current,
                isPlaying = playbackState.isPlaying,
                discSize = MINI_COVER_DP.dp,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (controlsVisible) {
            // 按下后图标转向，按钮让位给模式
            PlayModeToggleButton(
                playMode = playbackState.playMode,
                expanded = playModeSelectorExpanded,
                onExpandedChange = { expanded ->
                    playModeSelectorExpanded = expanded
                    resetAutoHide()
                },
                size = MINI_BUTTON_DP.dp,
                iconSize = 20.dp,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (playModeSelectorExpanded) {
                // 选中即切换模式并收起
                playModeMenuOrder.forEach { mode ->
                    PlayModeOptionButton(
                        mode = mode,
                        selected = mode == playbackState.playMode,
                        onClick = {
                            selectPlayMode(playbackState, mode)
                            playModeSelectorExpanded = false
                        },
                        size = MINI_BUTTON_DP.dp,
                        iconSize = 20.dp,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        selectedTint = MaterialTheme.colorScheme.primary,
                    )
                }
            } else {
                // 上一曲
                MiniControlButton(
                    icon = AppIcons.SkipPrevious,
                    contentDescription = stringResource(R.string.mini_player_previous),
                    enabled = playbackState.playlist.isNotEmpty(),
                    onClick = {
                        val prev = playbackState.previousIndex()
                        if (prev >= 0) {
                            scope.launch {
                                playTrackAt(context, playbackState, prev, switchKind = TrackSwitchKind.Previous)
                            }
                        }
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
                        if (next >= 0) {
                            scope.launch {
                                playTrackAt(context, playbackState, next, switchKind = TrackSwitchKind.Next)
                            }
                        }
                    }
                )
                // 展开状态下图标用强调色
                MiniControlButton(
                    icon = AppIcons.QueueMusic,
                    contentDescription = stringResource(R.string.mini_player_playlist),
                    onClick = onTogglePlaylist,
                    tint = if (playlistExpanded) MaterialTheme.colorScheme.primary else null,
                )
            }
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
                // 整块交叠淡出；小窗格子固定，横移会越界
                TrackSwitchTransition(
                    anchor = trackAnchor,
                    modifier = Modifier.fillMaxWidth(),
                    style = TrackSwitchStyle.Crossfade,
                ) { info ->
                    Column(modifier = Modifier.fillMaxWidth()) {
                        MarqueeText(
                            text = info.title,
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
                                text = info.artist,
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 10.sp,
                                lineHeight = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.Medium,
                            )
                        } else {
                            val lineEndMs = lyricLines
                                .getOrNull(lyricIndex + 1)
                                ?.timeMs ?: (lyricLine.timeMs + 3000L)
                            val lineDuration = (lineEndMs - lyricLine.timeMs).coerceAtLeast(1L)
                            // 行尾提前量：切行前完成点亮
                            val endLeadMs = lyricEndLeadMs(lyricLine, lineEndMs)
                            // 逐字按词时序；普通歌词按行时长均分
                            val progress = if (wordByWordEnabled && lyricLine.words.isNotEmpty()) {
                                val wordEnds = remember(lyricLine.words, lineEndMs, endLeadMs) {
                                    lyricWordEnds(lyricLine.words, lineEndMs, tailLeadMs = endLeadMs)
                                }
                                lyricWordFillFraction(lyricLine.words, wordEnds, lyricPosition)
                            } else {
                                ((lyricPosition - lyricLine.timeMs).toFloat() /
                                    (lineDuration - endLeadMs).toFloat())
                                    .coerceIn(0f, 1f)
                            }
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
                                lineKey = info.id to lyricIndex,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}

// 歌词行自左向右点亮；超宽时随点亮边缘平移
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
    // 进度平滑推进；换行归零防闪烁
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
                // 逐字按进度，整行恒为全宽
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

// 圆环小于触控热区，避免贴边
@Composable
private fun MiniControlButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    // 指定色用于标出持续状态
    tint: Color? = null,
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
                tint = tint
                    ?: if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
            )
        }
    }
}

// 歌词揭示平滑时长
private const val MINI_LYRIC_REVEAL_SMOOTH_MS = 60
// 超过此回退视为回卷或拖动
private const val MINI_LYRIC_SEEK_TOLERANCE_MS = 1500L