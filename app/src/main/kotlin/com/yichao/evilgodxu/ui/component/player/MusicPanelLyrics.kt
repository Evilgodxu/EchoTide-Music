package com.yichao.evilgodxu.ui.component.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import com.yichao.evilgodxu.data.music.metadata.MusicMetadataCache
import com.yichao.evilgodxu.data.music.model.LyricLine
import com.yichao.evilgodxu.data.settings.LyricAlignment
import com.yichao.evilgodxu.data.settings.LyricLayoutDefaults
import com.yichao.evilgodxu.data.settings.wordByWordRenderingFlow
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.navigation.LocalScreenActive
import com.yichao.evilgodxu.data.music.playback.seekToAndPlay
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.theme.LocalSuccessColor
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
internal fun LyricsPanel(
    playbackState: MusicPlaybackState,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    fontSize: TextUnit = 12.sp,
    contentColor: Color? = null,
    visibleLines: Int = DEFAULT_VISIBLE_LINES,
    // 行间距，按场景传入
    lineSpacing: Dp = LyricLayoutDefaults.LINE_SPACING_DEFAULT_DP.dp,
    // 水平对齐，三条渲染路径共用
    alignment: LyricAlignment = LyricAlignment.CENTER,
    // 上下边缘渐隐行数，自留白内缘起算
    fadeLines: Float = LyricLayoutDefaults.FADE_LINES_DEFAULT,
    // 渐隐留白行数，歌词视口内缩
    fadeInsetLines: Float = LyricLayoutDefaults.FADE_INSET_LINES_DEFAULT,
    // 纵向快速滑动切歌出口；null 时一律按歌词跳转
    onVerticalFling: ((next: Boolean) -> Unit)? = null,
    // 纵向拖拽接管回调，避免闪出标题栏
    onVerticalDragClaim: (() -> Unit)? = null,
) {
    // 已唱与未唱颜色；contentColor 可覆盖
    val activeColor = contentColor ?: MaterialTheme.colorScheme.primary
    val pendingColor = (contentColor ?: MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = 0.72f)
    // 逐字渲染开关，关闭后整行高亮
    val context = LocalContext.current
    val wordByWordEnabled by context.wordByWordRenderingFlow().collectAsState(initial = true)
    // 跟随曲目起算，起步取状态层歌词时间轴
    val seedPosition = remember(playbackState.currentTrack?.id) {
        playbackState.seedLyricPosition(playbackState.currentTrack?.id)
    }
    // 逐字填充按 vsync 推进；切行与滚动按固定间隔取样
    // 两者分开：高频值只在当前行内读取，重组不外溢到整列歌词
    val framePositionState = remember(playbackState.currentTrack?.id) {
        mutableLongStateOf(seedPosition)
    }
    var lyricPosition by remember(playbackState.currentTrack?.id) { mutableLongStateOf(seedPosition) }
    val livePosition = remember(framePositionState) { { framePositionState.longValue } }
    // 拖拽跳转后的短时保护窗，忽略旧位置
    var seekGuardUntilMs by remember(playbackState.currentTrack?.id) { mutableLongStateOf(0L) }
    // 播放页被翻走或被覆盖层压住时不逐帧推进，恢复后从当前相位继续
    val screenActive = LocalScreenActive.current
    LaunchedEffect(playbackState.isPlaying, playbackState.currentTrack?.id, screenActive) {
        // 本次跟随所属曲目
        val trackId = playbackState.currentTrack?.id
        var lastSyncMs = 0L
        var lastPublishMs = 0L
        while (isActive) {
            if (!playbackState.isPlaying || !screenActive) {
                // 暂停或不可见时不占 vsync：位置冻结，仅在外部 seek 时跟随
                lastSyncMs = 0L
                delay(LYRIC_PAUSED_POLL_MS)
                if (System.currentTimeMillis() < seekGuardUntilMs) continue
                val candidate = playbackState.lyricPositionMs
                val held = framePositionState.longValue
                framePositionState.longValue = when {
                    candidate >= held -> candidate
                    held - candidate > LYRIC_SEEK_TOLERANCE_MS -> candidate
                    else -> held
                }
                lyricPosition = framePositionState.longValue
                continue
            }
            // 与设备刷新率同拍推进，填充不再靠补间追赶
            withFrameNanos { }
            val now = System.currentTimeMillis()
            val elapsed = if (lastSyncMs == 0L) 0L else (now - lastSyncMs).coerceAtLeast(0L)
            lastSyncMs = now
            // 锚点取控制器即时位置，按绝对时间换算
            val candidate = playbackState.lyricPositionMs
            val held = framePositionState.longValue
            framePositionState.longValue = when {
                now < seekGuardUntilMs -> held + elapsed
                candidate >= held -> candidate
                held - candidate > LYRIC_SEEK_TOLERANCE_MS -> candidate
                else -> held + elapsed
            }
            // 切行、滚动与重建快照按固定间隔取样，不跟随逐帧抖动
            if (lastPublishMs == 0L || now - lastPublishMs >= LYRIC_POSITION_PUBLISH_MS) {
                lyricPosition = framePositionState.longValue
                lastPublishMs = now
                playbackState.recordLyricPosition(trackId, framePositionState.longValue)
            }
        }
    }

    val lines = playbackState.currentTrack?.lyricLines.orEmpty()
    // 手势协程常驻，始终读最新歌词
    val currentLines by rememberUpdatedState(lines)
    // 切歌出口同理
    val currentOnVerticalFling by rememberUpdatedState(onVerticalFling)
    // 纵向接管出口同理
    val currentOnVerticalDragClaim by rememberUpdatedState(onVerticalDragClaim)
    // 歌词缺失时按需补全
    LaunchedEffect(
        playbackState.currentTrack?.id,
        playbackState.currentTrack?.lyricCachePath,
        playbackState.currentTrack?.lyricLines?.size,
        playbackState.currentTrack?.lyricFailed,
    ) {
        playbackState.requestMetadata(playbackState.currentTrack)
    }
    val activeIndex = lines.indexOfLast { it.timeMs <= lyricPosition }.coerceAtLeast(0)
    // 当前行居中，上下各半
    val offset = visibleLines / 2
    // 视口固定为 N 行标准高，超出由渐隐裁剪
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // 行距像素，三处共用
    val lineSpacingPx = with(density) { lineSpacing.roundToPx() }
    // 靠左版式与标题左缘对齐
    val rowStartInset = if (alignment == LyricAlignment.START) LYRIC_ALIGNED_START_INSET else LYRIC_ROW_INSET
    // 标准行高，与渲染同口径
    val lyricTextStyle = LocalTextStyle.current
    val lyricLineHeightPx = remember(fontSize, density, lyricTextStyle) {
        textMeasurer.measure(
            AnnotatedString("歌词"),
            lyricTextStyle.merge(TextStyle(fontSize = fontSize, fontWeight = FontWeight.Normal)),
        ).size.height
    }
    val maxViewportHeight = remember(visibleLines, lyricLineHeightPx, lineSpacingPx) {
        val slotPx = lyricLineHeightPx + with(density) { 4.dp.roundToPx() }
        slotPx * visibleLines + lineSpacingPx * (visibleLines - 1)
    }
    val standardSlotHeight = with(density) { (lyricLineHeightPx + 4.dp.roundToPx()).toDp() }
    // 兜底行距，仅窗口未实测时使用
    val fallbackSlotPx = with(density) { standardSlotHeight.toPx() } + lineSpacingPx
    // 上下各内缩渐隐留白，最多只剩一行
    val effectiveInsetLines = fadeInsetLines.coerceIn(0f, (visibleLines - 1) / 2f)
    val blankHeight = (standardSlotHeight + lineSpacing) * effectiveInsetLines
    // 内缩后覆盖行数，用于换算渐隐比例
    val innerLines = (visibleLines - effectiveInsetLines * 2f).coerceAtLeast(1f)
    val viewportHeight = with(density) { maxViewportHeight.toDp() }

    // 浮点行号；普通状态承载动画输出
    var scrollPosition by remember(playbackState.currentTrack?.id) { mutableFloatStateOf(0f) }
    // 拖拽中或回弹中，均挂起跟随
    var scrubbing by remember(playbackState.currentTrack?.id) { mutableStateOf(false) }
    var settling by remember(playbackState.currentTrack?.id) { mutableStateOf(false) }
    // 拖拽前位置，未对齐时回弹目标
    var scrubStartPosition by remember(playbackState.currentTrack?.id) { mutableFloatStateOf(0f) }
    // 回弹代数，作废旧动画
    var settleGeneration by remember(playbackState.currentTrack?.id) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    // 像素位移换算行位移，测量时写入
    val dragGeometry = remember { LyricScrollGeometry() }

    // 跟随播放；跨度超过阈值直接定位
    LaunchedEffect(activeIndex, playbackState.currentTrack?.id, scrubbing, settling) {
        if (scrubbing || settling) return@LaunchedEffect
        val start = scrollPosition
        val target = activeIndex.toFloat()
        if (abs(target - start) <= LYRIC_SCROLL_MAX_STEP) {
            animate(
                initialValue = start,
                targetValue = target,
                animationSpec = tween(LYRIC_SCROLL_TRANSITION_MS, easing = FastOutSlowInEasing),
            ) { value, _ -> scrollPosition = value }
        } else {
            scrollPosition = target
        }
    }
    // 跨度大时直接用目标行，避免错误行居中
    val displayPosition = when {
        scrubbing || settling -> scrollPosition
        abs(scrollPosition - activeIndex) > LYRIC_SCROLL_MAX_STEP -> activeIndex.toFloat()
        else -> scrollPosition
    }
    // 已对齐到某行，标识转为确认色
    val scrubAligned = scrubbing && alignedScrubRow(scrollPosition, lines.lastIndex) != null

    // 换曲时歌词整体淡入
    val switchFade = remember(playbackState.currentTrack?.id) { Animatable(0f) }
    LaunchedEffect(playbackState.currentTrack?.id) {
        switchFade.animateTo(1f, tween(LYRIC_SWITCH_FADE_MS))
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = switchFade.value }
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            // 纵向拖拽调进度；方向优先，快速滑动留给切歌
            .pointerInput(playbackState.currentTrack?.id) {
                // 快速滑动阈值 600dp/s
                val flingVelocityPx = with(density) { LYRIC_FLING_VELOCITY_DP_S.dp.toPx() }
                // NaN 视为慢速，避免误判切歌
                fun isFling(velocityY: Float): Boolean = abs(velocityY) >= flingVelocityPx

                // 拖拽起点同 displayPosition，读实时状态
                fun liveDisplayPosition(): Float {
                    val liveActive = currentLines.indexOfLast { it.timeMs <= lyricPosition }
                        .coerceAtLeast(0)
                    return if (abs(scrollPosition - liveActive) > LYRIC_SCROLL_MAX_STEP) {
                        liveActive.toFloat()
                    } else {
                        scrollPosition
                    }
                }

                // 释放收尾：吸附或回弹，代数作废旧动画
                fun settleTo(target: Float, animationSpec: AnimationSpec<Float>) {
                    val generation = ++settleGeneration
                    settling = true
                    scope.launch {
                        animate(
                            initialValue = scrollPosition,
                            targetValue = target,
                            animationSpec = animationSpec,
                        ) { value, _ ->
                            if (settleGeneration == generation) scrollPosition = value
                        }
                        if (settleGeneration == generation) settling = false
                    }
                }

                // 像素位移除以行距得行位移
                fun advanceScrub(dragAmount: Float) {
                    val slot = dragGeometry.slotAt(scrollPosition)
                    scrollPosition = (scrollPosition - dragAmount / slot)
                        .coerceIn(0f, currentLines.lastIndex.toFloat())
                }

                // 确认慢速拖拽，补入累计位移
                fun beginScrub(pendingPx: Float) {
                    if (currentLines.isEmpty()) return
                    settleGeneration++
                    settling = false
                    scrollPosition = scrubStartPosition
                    scrubbing = true
                    advanceScrub(pendingPx)
                }

                fun finishScrub(cancelled: Boolean, flingVelocityY: Float) {
                    // 全程未确认跟随：只按速度决定切歌
                    if (!scrubbing) {
                        val fling = currentOnVerticalFling
                        if (!cancelled && fling != null && isFling(flingVelocityY)) {
                            fling(flingVelocityY < 0f)
                        }
                        return
                    }
                    scrubbing = false
                    if (currentLines.isEmpty()) return
                    // 确认后甩出也按切歌，先收回当前行
                    val fling = currentOnVerticalFling
                    if (!cancelled && fling != null && isFling(flingVelocityY)) {
                        val playIndex = currentLines.indexOfLast { it.timeMs <= lyricPosition }
                            .coerceAtLeast(0)
                        settleTo(playIndex.toFloat(), tween(LYRIC_SCRUB_SNAP_MS))
                        fling(flingVelocityY < 0f)
                        return
                    }
                    val candidate = alignedScrubRow(scrollPosition, currentLines.lastIndex)
                    if (!cancelled && candidate != null) {
                        // 对齐：跳到该行起播，并设保护窗
                        val targetMs = currentLines[candidate].timeMs
                        // 逐帧锚点同步落点，保护窗内才不会把取样位置拉回旧行
                        framePositionState.longValue = targetMs
                        lyricPosition = targetMs
                        seekGuardUntilMs = System.currentTimeMillis() + LYRIC_SCRUB_SEEK_GUARD_MS
                        // 绝对时间换算到播放器坐标系
                        seekToAndPlay(playbackState, playbackState.toPlayerPosition(targetMs))
                        settleTo(candidate.toFloat(), tween(LYRIC_SCRUB_SNAP_MS))
                    } else {
                        // 未对齐：回弹到拖拽前位置
                        settleTo(
                            target = scrubStartPosition,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                        )
                    }
                }

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // 速度采样，区分快速滑动与慢速拖拽
                    val velocityTracker = VelocityTracker()
                    velocityTracker.addPosition(down.uptimeMillis, down.position)
                    // 方向仲裁：纵向占优则接管消费
                    var dragY = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                            ?: return@awaitEachGesture
                        // 抬起或被消费则放弃手势
                        if (!change.pressed || change.isConsumed) return@awaitEachGesture
                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                        val delta = change.position - down.position
                        if (delta.getDistance() < viewConfiguration.touchSlop) continue
                        if (abs(delta.y) < abs(delta.x)) return@awaitEachGesture
                        dragY = delta.y
                        // 纵向接管，通知宿主收回控件
                        if (currentLines.isNotEmpty()) currentOnVerticalDragClaim?.invoke()
                        change.consume()
                        break
                    }
                    // 只记录起点，确认慢速后才接管
                    scrubStartPosition = liveDisplayPosition()
                    // 未生效的累计位移，确认时补给
                    var pendingScrubPx = dragY
                    // 无切歌出口时立即跟随手指
                    if (currentOnVerticalFling == null) {
                        beginScrub(pendingScrubPx)
                        pendingScrubPx = 0f
                    }
                    val ended = drag(down.id) { change ->
                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                        if (scrubbing) {
                            advanceScrub(change.positionChange().y)
                        } else {
                            pendingScrubPx += change.positionChange().y
                            // 速度落回阈值即确认慢速拖拽
                            if (currentLines.isNotEmpty() &&
                                !isFling(velocityTracker.calculateVelocity().y)
                            ) {
                                beginScrub(pendingScrubPx)
                                pendingScrubPx = 0f
                            }
                        }
                        change.consume()
                    }
                    finishScrub(
                        cancelled = !ended,
                        flingVelocityY = velocityTracker.calculateVelocity().y,
                    )
                }
            }
            .padding(top = 4.dp, bottom = 0.dp),
        contentAlignment = Alignment.Center
    ) {
        if (lines.isEmpty()) {
            // 仅真正缺歌词时显示提示
            val hasLyricCache = playbackState.currentTrack?.lyricCachePath
                ?.let { MusicMetadataCache.isValid(context, it) } == true
            if (!hasLyricCache) {
                Text(
                    stringResource(R.string.music_panel_no_lyrics),
                    color = contentColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
        } else {
            // 高度固定，溢出即裁剪
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(viewportHeight),
            ) {
                // 视口按留白内缩，边缘不贴字
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = blankHeight)
                        .clipToBounds()
                        // 上下边缘渐隐，比例按内缩行数换算
                        .verticalFadeMask(fadeFraction = fadeLines / innerLines)
                ) {
                    // 以显示行为锚点，上下多渲染 buffer 行
                    val anchorIndex = displayPosition.roundToInt().coerceIn(0, lines.lastIndex)
                    // 拖拽时高亮中线最近行
                    val highlightIndex = if (scrubbing) anchorIndex else activeIndex
                    val windowStart = anchorIndex - offset - LYRIC_WINDOW_BUFFER
                    LyricColumnLayout(
                        windowStart = windowStart,
                        animatedPosition = displayPosition,
                        maxViewportHeight = maxViewportHeight,
                        geometry = dragGeometry,
                        spacingPx = lineSpacingPx,
                        fallbackSlotPx = fallbackSlotPx,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        repeat(visibleLines + LYRIC_WINDOW_BUFFER * 2) { row ->
                            val index = windowStart + row
                            // 以行下标为键，保留行状态
                            key(index) {
                                val line = lines.getOrNull(index)
                                if (line == null) {
                                    LyricSpacer(height = standardSlotHeight)
                                } else {
                                    val isCurrent = index == highlightIndex
                                    val emphasis by animateFloatAsState(
                                        targetValue = if (isCurrent) 1f else 0f,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioNoBouncy,
                                            stiffness = Spring.StiffnessMediumLow
                                        ),
                                        label = "lyric_emphasis"
                                    )
                                    val scale = LYRIC_ROW_SCALE_BASE + LYRIC_ROW_SCALE_AMPLITUDE * emphasis
                                    // 非当前行降低不透明度
                                    val rowAlpha = LYRIC_INACTIVE_ALPHA + (1f - LYRIC_INACTIVE_ALPHA) * emphasis
                                    val nextTimeMs = lines.getOrNull(index + 1)?.timeMs ?: line.timeMs + 3000L
                                    LyricText(
                                        line = line,
                                        nextTimeMs = nextTimeMs,
                                        positionMs = lyricPosition,
                                        // 当前行逐帧跟随，其余行按取样值
                                        livePositionMs = livePosition,
                                        isCurrent = isCurrent,
                                        wordByWordEnabled = wordByWordEnabled,
                                        fontSize = fontSize,
                                        fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal,
                                        activeColor = activeColor,
                                        pendingColor = pendingColor,
                                        alignment = alignment,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            // 留白在缩放层外，靠左放大只向右
                                            .padding(
                                                start = rowStartInset,
                                                top = 2.dp,
                                                end = LYRIC_ROW_INSET,
                                                bottom = 2.dp,
                                            )
                                            .graphicsLayer {
                                                alpha = rowAlpha
                                                scaleX = scale
                                                scaleY = scale
                                                transformOrigin = if (alignment == LyricAlignment.START) {
                                                    TransformOrigin(0f, 0.5f)
                                                } else {
                                                    TransformOrigin.Center
                                                }
                                            }
                                    )
                                }
                            }
                        }
                    }
                }
                // 拖拽基准标识，固定中线，对齐转换确认色
                LyricScrubMarker(
                    visible = scrubbing,
                    color = activeColor,
                    aligned = scrubAligned,
                )
            }
        }
    }
}

@Composable
internal fun LyricSpacer(height: Dp) {
    Spacer(modifier = Modifier.height(height))
}

// 中线两侧短横线；对齐时变确认色
@Composable
private fun BoxScope.LyricScrubMarker(visible: Boolean, color: Color, aligned: Boolean) {
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(LYRIC_SCRUB_MARKER_FADE_MS),
        label = "lyric_scrub_marker",
    )
    if (alpha <= 0.01f) return
    // 对齐确认色过渡
    val confirmColor = LocalSuccessColor.current
    val dashColor by animateColorAsState(
        targetValue = if (aligned) confirmColor else color,
        animationSpec = tween(LYRIC_SCRUB_MARKER_CONFIRM_MS),
        label = "lyric_scrub_marker_aligned",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .align(Alignment.Center)
            .graphicsLayer { this.alpha = alpha },
    ) {
        LyricScrubDash(color = dashColor, modifier = Modifier.align(Alignment.CenterStart))
        LyricScrubDash(color = dashColor, modifier = Modifier.align(Alignment.CenterEnd))
    }
}

@Composable
private fun LyricScrubDash(color: Color, modifier: Modifier) {
    Box(
        modifier = modifier
            .width(LYRIC_SCRUB_DASH_WIDTH)
            .height(LYRIC_SCRUB_DASH_HEIGHT)
            .background(
                color = color.copy(alpha = 0.8f),
                shape = RoundedCornerShape(LYRIC_SCRUB_DASH_HEIGHT / 2),
            ),
    )
}

// 拖拽换算几何，测量写入，普通对象
private class LyricScrollGeometry {
    private var windowStart = 0
    private var rowHeights: IntArray = EMPTY_ROW_HEIGHTS
    private var spacingPx = 0
    private var fallbackSlotPx = 1f

    fun update(windowStart: Int, rowHeights: IntArray, spacingPx: Int, fallbackSlotPx: Float) {
        this.windowStart = windowStart
        this.rowHeights = rowHeights
        this.spacingPx = spacingPx
        this.fallbackSlotPx = fallbackSlotPx
    }

    // 当前行到下一行中心的距离
    fun slotAt(position: Float): Float {
        val heights = rowHeights
        if (heights.isEmpty()) return fallbackSlotPx.coerceAtLeast(1f)
        val row = floor(position - windowStart).toInt().coerceIn(0, heights.lastIndex)
        val height = heights[row].toFloat()
        val nextHeight = heights.getOrNull(row + 1)?.toFloat() ?: height
        return (height / 2f + spacingPx + nextHeight / 2f).coerceAtLeast(1f)
    }
}

private val EMPTY_ROW_HEIGHTS = IntArray(0)

// 按真实行高排布，目标中心对齐中线。
@Composable
private fun LyricColumnLayout(
    windowStart: Int,
    animatedPosition: Float,
    maxViewportHeight: Int,
    geometry: LyricScrollGeometry,
    // 行距像素，与视口高度同源
    spacingPx: Int,
    fallbackSlotPx: Float,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(
        modifier = modifier,
        content = content,
    ) { measurables, constraints ->
        val placeables = measurables.map {
            it.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
        }
        // 写入实测几何，不触发重组
        geometry.update(
            windowStart = windowStart,
            rowHeights = IntArray(placeables.size) { placeables[it].height },
            spacingPx = spacingPx,
            fallbackSlotPx = fallbackSlotPx,
        )
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth
        else placeables.maxOfOrNull { it.width } ?: 0
        // 视口固定 N 行，buffer 行不撑高
        val boundedMax = if (constraints.hasBoundedHeight) constraints.maxHeight else Int.MAX_VALUE
        val viewportHeight = minOf(maxViewportHeight, boundedMax).coerceAtLeast(1)
        if (placeables.isEmpty()) {
            layout(width, viewportHeight) {}
        } else {
            // 行号取整定位，余数用于插值
            val relative = (animatedPosition - windowStart)
                .coerceIn(0f, placeables.lastIndex.toFloat())
            val row = floor(relative).toInt().coerceIn(0, placeables.lastIndex)
            val fraction = relative - row
            var rowTop = 0
            for (i in 0 until row) rowTop += placeables[i].height + spacingPx
            val rowCenter = rowTop + placeables[row].height / 2f
            val rowToNextCenter = if (row < placeables.lastIndex) {
                placeables[row].height / 2f + spacingPx + placeables[row + 1].height / 2f
            } else {
                0f
            }
            // 平移使目标位置居中
            val shift = viewportHeight / 2f - (rowCenter + fraction * rowToNextCenter)
            layout(width, viewportHeight) {
                var y = shift
                placeables.forEachIndexed { i, placeable ->
                    placeable.placeRelative(0, y.roundToInt())
                    y += placeable.height + if (i < placeables.lastIndex) spacingPx else 0
                }
            }
        }
    }
}

@Composable
internal fun LyricText(
    line: LyricLine,
    nextTimeMs: Long,
    positionMs: Long,
    isCurrent: Boolean,
    wordByWordEnabled: Boolean,
    fontSize: TextUnit,
    fontWeight: FontWeight,
    activeColor: Color,
    pendingColor: Color,
    alignment: LyricAlignment,
    modifier: Modifier = Modifier,
    // 当前行的逐帧位置；为空时沿用 positionMs
    // 在此读取，状态依赖只落在行内，不外溢到整列歌词
    livePositionMs: (() -> Long)? = null,
) {
    val effectivePositionMs =
        if (isCurrent && livePositionMs != null) livePositionMs() else positionMs
    var contentWidthPx by remember { mutableIntStateOf(0) }
    // 统一按放大预留宽度分行，避免换行跳变
    val wrapWidthPx = if (contentWidthPx <= 0) 0 else (contentWidthPx / LYRIC_ROW_SCALE_MAX).roundToInt()
    // 水平缩放余量全行统一
    val reserveWidthPx = (contentWidthPx * (LYRIC_ROW_SCALE_MAX - 1f) / (2f * LYRIC_ROW_SCALE_MAX)).roundToInt()
    val reserveWidth = with(LocalDensity.current) { reserveWidthPx.toDp() }
    Column(
        modifier = modifier.onSizeChanged { contentWidthPx = it.width },
        horizontalAlignment = alignment.columnAlignment,
    ) {
        if (!wordByWordEnabled) {
            WholeLineLyricText(
                line = line,
                isCurrent = isCurrent,
                fontSize = fontSize,
                fontWeight = fontWeight,
                activeColor = activeColor,
                pendingColor = pendingColor,
                widthPx = wrapWidthPx,
                alignment = alignment,
            )
        } else if (line.words.isNotEmpty()) {
            WordSplitLyricText(
                line = line,
                nextTimeMs = nextTimeMs,
                positionMs = effectivePositionMs,
                isCurrent = isCurrent,
                fontSize = fontSize,
                fontWeight = fontWeight,
                activeColor = activeColor,
                pendingColor = pendingColor,
                widthPx = wrapWidthPx,
                alignment = alignment,
            )
        } else {
            LineFillLyricText(
                line = line,
                nextTimeMs = nextTimeMs,
                positionMs = effectivePositionMs,
                isCurrent = isCurrent,
                fontSize = fontSize,
                fontWeight = fontWeight,
                activeColor = activeColor,
                pendingColor = pendingColor,
                widthPx = wrapWidthPx,
                alignment = alignment,
            )
        }
        line.translation?.takeIf { it.isNotBlank() }?.let { translation ->
            // 译文小字号静置，当前行同步高亮
            Text(
                text = translation,
                fontSize = (fontSize.value * 0.68f).sp,
                fontWeight = FontWeight.Normal,
                color = if (isCurrent) activeColor else pendingColor.copy(alpha = 0.55f),
                style = if (isCurrent) {
                    TextStyle(shadow = Shadow(activeColor.copy(alpha = 0.65f), blurRadius = 5f))
                } else {
                    TextStyle()
                },
                textAlign = alignment.lineTextAlign,
                softWrap = true,
                modifier = Modifier
                    .fillMaxWidth()
                    // 居中两侧对称留白，靠左只留行尾
                    .padding(
                        start = if (alignment == LyricAlignment.START) 0.dp else reserveWidth,
                        end = reserveWidth,
                    )
                    .padding(top = 1.dp),
            )
        }
    }
}

// 关闭逐字：整行着色，仍用手写分行
@Composable
private fun WholeLineLyricText(
    line: LyricLine,
    isCurrent: Boolean,
    fontSize: TextUnit,
    fontWeight: FontWeight,
    activeColor: Color,
    pendingColor: Color,
    widthPx: Int,
    alignment: LyricAlignment,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val wrapStyle = LocalTextStyle.current.merge(
        TextStyle(fontSize = fontSize, fontWeight = LYRIC_WRAP_FONT_WEIGHT),
    )
    val rows = remember(line.text, widthPx, wrapStyle) {
        if (widthPx <= 0) listOf(line.text) else wrapLyricText(line.text, widthPx, textMeasurer, wrapStyle)
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = alignment.columnAlignment,
    ) {
        rows.forEach { row ->
            Text(
                text = row,
                fontSize = fontSize,
                fontWeight = fontWeight,
                color = if (isCurrent) activeColor else pendingColor,
                textAlign = alignment.lineTextAlign,
                // 已手动分行，按单行呈现
                softWrap = false,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// 位置回退容差，小于视为抖动
private const val LYRIC_SEEK_TOLERANCE_MS = 1500L

// 切行与滚动取样的间隔；逐字填充另有逐帧通道
private const val LYRIC_POSITION_PUBLISH_MS = 50L

// 暂停时的外部跳转轮询间隔，不占 vsync
private const val LYRIC_PAUSED_POLL_MS = 200L

// 可见行数，奇数使当前行居中
private const val DEFAULT_VISIBLE_LINES = 5

// 居中版式两侧留白
private val LYRIC_ROW_INSET = 6.dp

// 靠左版式起始留白，与标题对齐
private val LYRIC_ALIGNED_START_INSET = 32.dp

// 换曲歌词淡入 260ms，短于封面
private const val LYRIC_SWITCH_FADE_MS = 260

// 上下各多渲染 2 行，平移连续
private const val LYRIC_WINDOW_BUFFER = 2

// 跨度超 2 行直接定位
private const val LYRIC_SCROLL_MAX_STEP = 2

// 非当前行不透明度 0.55
private const val LYRIC_INACTIVE_ALPHA = 0.55f

// 释放对齐容差 0.35 行
private const val LYRIC_SCRUB_SNAP_ROWS = 0.35f

// 吸附动画 220ms
private const val LYRIC_SCRUB_SNAP_MS = 220

// 快速滑动切歌阈值 600dp/s
private const val LYRIC_FLING_VELOCITY_DP_S = 600f

// 对齐跳转后保护窗 1000ms
private const val LYRIC_SCRUB_SEEK_GUARD_MS = 1000L

// 基准标识尺寸与过渡时长
private const val LYRIC_SCRUB_MARKER_FADE_MS = 160
private const val LYRIC_SCRUB_MARKER_CONFIRM_MS = 120
private val LYRIC_SCRUB_DASH_WIDTH = 22.dp
private val LYRIC_SCRUB_DASH_HEIGHT = 2.dp

// 当前对齐行，偏差超容差为 null
private fun alignedScrubRow(position: Float, lastIndex: Int): Int? {
    if (lastIndex < 0) return null
    val candidate = position.roundToInt().coerceIn(0, lastIndex)
    return candidate.takeIf { abs(position - candidate) <= LYRIC_SCRUB_SNAP_ROWS }
}

// 上下边缘 DstIn 渐隐，两端留全透明保护带
private fun Modifier.verticalFadeMask(fadeFraction: Float = 0.25f): Modifier = drawWithCache {
    val fade = fadeFraction.coerceIn(0f, MAX_FADE_EDGE_FRACTION)
    // 完全透明保护带宽度
    val guard = fade * 0.12f
    val brush = Brush.verticalGradient(
        colorStops = arrayOf(
            0f to Color.Transparent,
            guard to Color.Transparent,
            fade to Color.Black,
            1f - fade to Color.Black,
            1f - guard to Color.Transparent,
            1f to Color.Transparent,
        )
    )
    onDrawWithContent {
        val bounds = Rect(Offset.Zero, size)
        // 裁剪与蒙层同层，避免边缘残影
        drawIntoCanvas { canvas ->
            canvas.save()
            canvas.clipRect(bounds)
            canvas.saveLayer(bounds, Paint())
        }
        drawContent()
        drawRect(brush = brush, size = size, blendMode = BlendMode.DstIn)
        drawIntoCanvas { canvas ->
            canvas.restore()
            canvas.restore()
        }
    }
}

// 渐隐比例上限，保证中段全亮
private const val MAX_FADE_EDGE_FRACTION = 0.45f
