package com.yichao.evilgodxu.ui.component.player

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import com.yichao.evilgodxu.data.music.metadata.MusicMetadataCache
import com.yichao.evilgodxu.data.music.model.LyricLine
import com.yichao.evilgodxu.data.settings.wordByWordRenderingFlow
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.data.music.playback.seekToAndPlay
import com.yichao.evilgodxu.R
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
) {
    // 已唱 / 未唱歌词颜色：默认取主题色，传入 contentColor 时（如首页）覆盖为指定色
    val activeColor = contentColor ?: MaterialTheme.colorScheme.primary
    val pendingColor = (contentColor ?: MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = 0.72f)
    // 逐字渲染开关：关闭后整行高亮，不再逐字跳动
    val context = LocalContext.current
    val wordByWordEnabled by context.wordByWordRenderingFlow().collectAsState(initial = true)
    // 跟随当前曲目：切换歌曲时重置到曲目起点，避免沿用上一首的播放位置定位错行
    var lyricPosition by remember(playbackState.currentTrack?.id) { mutableLongStateOf(0L) }
    // 歌词拖拽跳转后的短时保护窗：窗内本地进度自走、忽略控制器的旧位置，
    // 避免 seek 回报前把刚拖到的行又拉回拖拽前位置
    var seekGuardUntilMs by remember(playbackState.currentTrack?.id) { mutableLongStateOf(0L) }
    LaunchedEffect(playbackState.isPlaying, playbackState.currentTrack?.id) {
        var lastSyncMs = 0L
        while (isActive) {
            val candidate = playbackState.mediaController?.currentPosition
                ?.takeIf { it >= 0L }
                ?: playbackState.currentPosition
            val guarding = System.currentTimeMillis() < seekGuardUntilMs
            if (playbackState.isPlaying) {
                val now = System.currentTimeMillis()
                val elapsed = if (lastSyncMs == 0L) 0L else (now - lastSyncMs).coerceAtLeast(0L)
                // 播放中以真实流逝时间推进，控制器位置仅作锚点：熄屏唤醒后控制器
                // 位置可能停滞，本地位置仍持续前进避免冻结；大幅回退视为手动拖动
                lyricPosition = when {
                    guarding -> lyricPosition + elapsed
                    candidate >= lyricPosition -> candidate
                    lyricPosition - candidate > LYRIC_SEEK_TOLERANCE_MS -> candidate
                    else -> lyricPosition + elapsed
                }
                lastSyncMs = now
            } else {
                // 暂停时保持本地已推进的真实位置，仅当控制器位置前移（正向 seek）或
                // 大幅回退（手动拖动）时跟随；避免把播放期间已领先于控制器滞后回报的
                // 本地位置拉回，导致已唱完的歌词高亮回退
                if (!guarding) {
                    lyricPosition = when {
                        candidate >= lyricPosition -> candidate
                        lyricPosition - candidate > LYRIC_SEEK_TOLERANCE_MS -> candidate
                        else -> lyricPosition
                    }
                }
                lastSyncMs = 0L
            }
            delay(if (playbackState.isPlaying) 50L else 200L)
        }
    }

    val lines = playbackState.currentTrack?.lyricLines.orEmpty()
    // 手势回调在整个 pointerInput 生命周期内保持有效，用 rememberUpdatedState 保证始终读到最新歌词
    val currentLines by rememberUpdatedState(lines)
    // 歌词缺失时按需补全（懒加载）：补全成功后回写 lyricLines 驱动重组显示
    LaunchedEffect(
        playbackState.currentTrack?.id,
        playbackState.currentTrack?.lyricCachePath,
        playbackState.currentTrack?.lyricLines?.size,
        playbackState.currentTrack?.lyricFailed,
    ) {
        playbackState.requestMetadata(playbackState.currentTrack)
    }
    val activeIndex = lines.indexOfLast { it.timeMs <= lyricPosition }.coerceAtLeast(0)
    // 当前行居中，上下各显示 (total-1)/2 行（total 为奇数）
    val offset = visibleLines / 2
    // 视口高度按 N 行标准高度计算：超长句换行与译文叠层会让单行高于标准，
    // 视口固定为 N 行标准高，超出部分交由边缘渐隐与裁剪处理
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // 标准单行高度（含行内上下内边距）：视口上限与窗口内占位行共用，使滚动基准线不随占位行变化。
    // 必须与 LyricChar/Text 的实际渲染同口径——沿用 LocalTextStyle（bodyLarge 自带 24.sp 行高）合并，
    // 否则裸 TextStyle 测出的高度偏小，视口按偏小行距排布会被实际更高的行撑出裁剪，可见行数少于设定值
    val lyricTextStyle = LocalTextStyle.current
    val lyricLineHeightPx = remember(fontSize, density, lyricTextStyle) {
        textMeasurer.measure(
            AnnotatedString("歌词"),
            lyricTextStyle.merge(TextStyle(fontSize = fontSize, fontWeight = FontWeight.Normal)),
        ).size.height
    }
    val maxViewportHeight = remember(visibleLines, lyricLineHeightPx, density) {
        val slotPx = lyricLineHeightPx + with(density) { 4.dp.roundToPx() }
        val spacingPx = with(density) { LYRIC_LINE_SPACING.roundToPx() }
        slotPx * visibleLines + spacingPx * (visibleLines - 1)
    }
    val standardSlotHeight = with(density) { (lyricLineHeightPx + 4.dp.roundToPx()).toDp() }
    // 拖拽换算的兜底行距：仅在窗口尚无实测行高时使用，正常路径均由实测行高换算
    val fallbackSlotPx = with(density) { standardSlotHeight.toPx() + LYRIC_LINE_SPACING.toPx() }

    // 显示位置（浮点行号）：正常播放由跟随动画推进，拖拽与回弹期间由手势/回弹动画驱动。
    // 用普通状态直接承载动画输出，释放瞬间即可同步写入，避免显示回退到过期的动画值
    var scrollPosition by remember(playbackState.currentTrack?.id) { mutableFloatStateOf(0f) }
    // scrubbing 为拖拽中，settling 为释放后的吸附/回弹动画中；两者进行时都挂起跟随 Effect
    var scrubbing by remember(playbackState.currentTrack?.id) { mutableStateOf(false) }
    var settling by remember(playbackState.currentTrack?.id) { mutableStateOf(false) }
    // 拖拽起始位置：未对齐释放时的回弹目标
    var scrubStartPosition by remember(playbackState.currentTrack?.id) { mutableFloatStateOf(0f) }
    // 回弹代数：令被新拖拽打断的旧回弹动画失效，避免它把位置或 settling 改回去
    var settleGeneration by remember(playbackState.currentTrack?.id) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    // 像素位移 → 行位移的换算依据：由 LyricColumnLayout 测量时写入的普通对象，手势回调只读
    val dragGeometry = remember { LyricScrollGeometry() }

    // 跟随播放：拖拽/回弹期间挂起，避免与手势、回弹动画争抢同一个位置。
    // 跨度大于阈值（拖动进度/切歌）时直接定位，避免长距离滚动
    LaunchedEffect(activeIndex, playbackState.currentTrack?.id, scrubbing, settling) {
        if (scrubbing || settling) return@LaunchedEffect
        val start = scrollPosition
        val target = activeIndex.toFloat()
        if (abs(target - start) <= LYRIC_SCROLL_MAX_STEP) {
            animate(
                initialValue = start,
                targetValue = target,
                animationSpec = tween(LYRIC_SCROLL_DURATION_MS, easing = FastOutSlowInEasing),
            ) { value, _ -> scrollPosition = value }
        } else {
            scrollPosition = target
        }
    }
    // 显示位置：拖拽/回弹期间即手势位置；其余时间跟随播放。与目标跨度较大时（拖动进度/切歌瞬间）
    // 跟随动画尚未归位，本帧直接用目标行定位，避免错误行被居中
    val displayPosition = when {
        scrubbing || settling -> scrollPosition
        abs(scrollPosition - activeIndex) > LYRIC_SCROLL_MAX_STEP -> activeIndex.toFloat()
        else -> scrollPosition
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            // 纵向拖拽调进度：过触摸 slop 后消费事件。声明在 combinedClickable 之后（内层），
            // 拖拽时取消点击，同时不触发外层左右滑动切面板；点按/长按不受影响
            .pointerInput(playbackState.currentTrack?.id) {
                // 拖拽起点：与组合期的 displayPosition 同口径，但读实时状态避免闭包过期
                fun liveDisplayPosition(): Float {
                    val liveActive = currentLines.indexOfLast { it.timeMs <= lyricPosition }
                        .coerceAtLeast(0)
                    return if (abs(scrollPosition - liveActive) > LYRIC_SCROLL_MAX_STEP) {
                        liveActive.toFloat()
                    } else {
                        scrollPosition
                    }
                }

                // 释放后的收尾动画：吸附到目标行或弹性回弹。
                // 代数用于让被新拖拽打断的旧动画失效，避免它把位置或 settling 改回去
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

                fun finishScrub(cancelled: Boolean) {
                    if (!scrubbing) return
                    scrubbing = false
                    if (currentLines.isEmpty()) return
                    val candidate = scrollPosition.roundToInt().coerceIn(0, currentLines.lastIndex)
                    if (!cancelled && abs(scrollPosition - candidate) <= LYRIC_SCRUB_SNAP_ROWS) {
                        // 对齐：吸附到该行并从该行时间点起播。先把当前行切到目标行并开短时保护窗
                        // 忽略控制器旧位置，避免跟随 Effect 在 seek 回报前把内容拉回拖拽前的行
                        val targetMs = currentLines[candidate].timeMs
                        lyricPosition = targetMs
                        seekGuardUntilMs = System.currentTimeMillis() + LYRIC_SCRUB_SEEK_GUARD_MS
                        seekToAndPlay(playbackState, targetMs)
                        settleTo(candidate.toFloat(), tween(LYRIC_SCRUB_SNAP_MS))
                    } else {
                        // 未对齐：不跳转进度，弹性回弹到拖拽前位置
                        settleTo(
                            target = scrubStartPosition,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                        )
                    }
                }

                detectVerticalDragGestures(
                    onDragStart = {
                        if (currentLines.isNotEmpty()) {
                            // 新的拖拽作废未完成的回弹，并立即接管显示位置
                            settleGeneration++
                            settling = false
                            scrubStartPosition = liveDisplayPosition()
                            scrollPosition = scrubStartPosition
                            scrubbing = true
                        }
                    },
                    onVerticalDrag = { change, dragAmount ->
                        if (scrubbing) {
                            change.consume()
                            // 像素位移 ÷ 当前行到下一行中心的距离 = 行位移，行高不定也能跟手
                            val slot = dragGeometry.slotAt(scrollPosition)
                            scrollPosition = (scrollPosition - dragAmount / slot)
                                .coerceIn(0f, currentLines.lastIndex.toFloat())
                        }
                    },
                    onDragEnd = { finishScrub(cancelled = false) },
                    onDragCancel = { finishScrub(cancelled = true) },
                )
            }
            .padding(top = 4.dp, bottom = 0.dp),
        contentAlignment = Alignment.Center
    ) {
        if (lines.isEmpty()) {
            // 关联歌词缓存存在时读取挂载极快，不闪"暂无歌词"占位；仅真正缺失时提示
            val hasLyricCache = playbackState.currentTrack?.lyricCachePath
                ?.let { MusicMetadataCache.isValid(it) } == true
            if (!hasLyricCache) {
                Text(
                    stringResource(R.string.music_panel_no_lyrics),
                    color = contentColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
        } else {
            // 窗口容器固定，滚动与渐变都在其内部进行：行溢出与滚出内容经过边缘即被渐隐裁剪
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clipToBounds()
                    .verticalFadeMask(fadeFraction = FADE_TOTAL_LINES / visibleLines),
            ) {
                // 以显示位置所在行为窗口锚点，上下各多渲染 buffer 行：滚动或拖拽时新行已在窗口内、
                // 被移除的行已完全移出视口，两者在同一坐标系整体平移，因此只会连续上移，不会突兀替换
                val anchorIndex = displayPosition.roundToInt().coerceIn(0, lines.lastIndex)
                // 拖拽时高亮跟随中线最近的候选行，作为「释放会选中哪一行」的反馈；其余时间跟随播放
                val highlightIndex = if (scrubbing) anchorIndex else activeIndex
                val windowStart = anchorIndex - offset - LYRIC_WINDOW_BUFFER
                LyricColumnLayout(
                    windowStart = windowStart,
                    animatedPosition = displayPosition,
                    maxViewportHeight = maxViewportHeight,
                    geometry = dragGeometry,
                    fallbackSlotPx = fallbackSlotPx,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    repeat(visibleLines + LYRIC_WINDOW_BUFFER * 2) { row ->
                        val index = windowStart + row
                        // 以行下标为键：窗口平移时同一行的状态（高亮进度等）得以保留，不会跳变
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
                                // 非当前行整体降低不透明度，弱化其视觉存在感；随高亮进度平滑过渡
                                val rowAlpha = LYRIC_INACTIVE_ALPHA + (1f - LYRIC_INACTIVE_ALPHA) * emphasis
                                val nextTimeMs = lines.getOrNull(index + 1)?.timeMs ?: line.timeMs + 3000L
                                LyricText(
                                    line = line,
                                    nextTimeMs = nextTimeMs,
                                    positionMs = lyricPosition,
                                    isCurrent = isCurrent,
                                    wordByWordEnabled = wordByWordEnabled,
                                    fontSize = fontSize,
                                    fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal,
                                    activeColor = activeColor,
                                    pendingColor = pendingColor,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .graphicsLayer {
                                            alpha = rowAlpha
                                            scaleX = scale
                                            scaleY = scale
                                        }
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
                // 拖拽基准标识：固定在视口中线、不随内容滚动，仅拖拽时淡入
                LyricScrubMarker(visible = scrubbing, color = activeColor)
            }
        }
    }
}

@Composable
internal fun LyricSpacer(height: Dp) {
    Spacer(modifier = Modifier.height(height))
}

// 拖拽调进度的基准标识：视口中线两侧各一段短横线（形如「- 歌词 -」），仅拖拽时淡入，
// 作为「哪一行与中线对齐会被选中」的参考线
@Composable
private fun BoxScope.LyricScrubMarker(visible: Boolean, color: Color) {
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(LYRIC_SCRUB_MARKER_FADE_MS),
        label = "lyric_scrub_marker",
    )
    if (alpha <= 0.01f) return
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .align(Alignment.Center)
            .graphicsLayer { this.alpha = alpha },
    ) {
        LyricScrubDash(color = color, modifier = Modifier.align(Alignment.CenterStart))
        LyricScrubDash(color = color, modifier = Modifier.align(Alignment.CenterEnd))
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

// 拖拽像素 → 行位移的换算依据：由 LyricColumnLayout 测量时写入，手势回调只读。
// 走普通对象而非 Compose 状态，避免手势期间写入测量结果触发额外重组
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

    // 当前位置所在行到下一行中心的距离：像素位移 ÷ 该值 = 行位移
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

// 歌词纵向布局：窗口内按真实行高逐行排布，再以“动画浮点行号”定位目标位置在内容中的中心，
// 整体平移使该中心对齐视口中线。行高随换行而不同，按固定行高偏移会使当前行偏离中线，
// 故用实测高度换算；目标中心在相邻两行中心之间线性插值，跨行切换不会跳变。
@Composable
private fun LyricColumnLayout(
    windowStart: Int,
    animatedPosition: Float,
    maxViewportHeight: Int,
    geometry: LyricScrollGeometry,
    fallbackSlotPx: Float,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val spacingPx = with(LocalDensity.current) { LYRIC_LINE_SPACING.roundToPx() }
    Layout(
        modifier = modifier,
        content = content,
    ) { measurables, constraints ->
        val placeables = measurables.map {
            it.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
        }
        // 把手势换算所需的实测几何写入普通对象：拖拽时按真实行距换算，且不触发额外重组
        geometry.update(
            windowStart = windowStart,
            rowHeights = IntArray(placeables.size) { placeables[it].height },
            spacingPx = spacingPx,
            fallbackSlotPx = fallbackSlotPx,
        )
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth
        else placeables.maxOfOrNull { it.width } ?: 0
        // 视口高度固定为 N 行标准高度：窗口内多出的 buffer 行不撑高面板，滚动基准线保持稳定
        val boundedMax = if (constraints.hasBoundedHeight) constraints.maxHeight else Int.MAX_VALUE
        val viewportHeight = minOf(maxViewportHeight, boundedMax).coerceAtLeast(1)
        if (placeables.isEmpty()) {
            layout(width, viewportHeight) {}
        } else {
            // 动画行号换算成窗口内相对行号，取整定位所在行，余数用于在相邻行中心之间插值
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
            // 平移量 = 视口中线 - 动画行号对应位置的中心，使该位置始终居于中线
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
    modifier: Modifier = Modifier,
) {
    var contentWidthPx by remember { mutableIntStateOf(0) }
    // 所有行统一按当前行的放大预留宽度分行：未唱/已唱和正在唱的行数一致，避免换行跳变
    val wrapWidthPx = if (contentWidthPx <= 0) 0 else (contentWidthPx / LYRIC_ROW_SCALE_MAX).roundToInt()
    // 各行的水平缩放余量同样全行统一，保证分行宽度与主歌词排版一致
    val reserveWidthPx = (contentWidthPx * (LYRIC_ROW_SCALE_MAX - 1f) / (2f * LYRIC_ROW_SCALE_MAX)).roundToInt()
    Column(
        modifier = modifier.onSizeChanged { contentWidthPx = it.width },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!wordByWordEnabled) {
            WholeLineLyricText(
                line = line,
                isCurrent = isCurrent,
                fontSize = fontSize,
                fontWeight = fontWeight,
                activeColor = activeColor,
                pendingColor = pendingColor,
                reserveWidthPx = reserveWidthPx,
            )
        } else if (line.words.isNotEmpty()) {
            WordSplitLyricText(
                line = line,
                nextTimeMs = nextTimeMs,
                positionMs = positionMs,
                isCurrent = isCurrent,
                fontSize = fontSize,
                fontWeight = fontWeight,
                activeColor = activeColor,
                pendingColor = pendingColor,
                widthPx = wrapWidthPx,
            )
        } else {
            LineFillLyricText(
                line = line,
                nextTimeMs = nextTimeMs,
                positionMs = positionMs,
                isCurrent = isCurrent,
                fontSize = fontSize,
                fontWeight = fontWeight,
                activeColor = activeColor,
                pendingColor = pendingColor,
                widthPx = wrapWidthPx,
            )
        }
        line.translation?.takeIf { it.isNotBlank() }?.let { translation ->
            // 翻译行以更小字号静置展示，主歌词高亮时翻译同步使用完整高亮色与发光
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
                textAlign = TextAlign.Center,
                softWrap = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = with(LocalDensity.current) { reserveWidthPx.toDp() })
                    .padding(top = 1.dp),
            )
        }
    }
}

// 关闭逐字渲染：整行统一着色，不逐字填充也不跳动
@Composable
private fun WholeLineLyricText(
    line: LyricLine,
    isCurrent: Boolean,
    fontSize: TextUnit,
    fontWeight: FontWeight,
    activeColor: Color,
    pendingColor: Color,
    reserveWidthPx: Int,
    modifier: Modifier = Modifier,
) {
    Text(
        text = line.text,
        fontSize = fontSize,
        fontWeight = fontWeight,
        color = if (isCurrent) activeColor else pendingColor,
        textAlign = TextAlign.Center,
        softWrap = true,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = with(LocalDensity.current) { reserveWidthPx.toDp() }),
    )
}

// 播放中位置回退容差：小于该值视为控制器位置抖动，大于视为手动拖动进度条
private const val LYRIC_SEEK_TOLERANCE_MS = 1500L

// 歌词面板默认可见行数：保持奇数使当前行垂直居中（上下各 (n-1)/2 行）
private const val DEFAULT_VISIBLE_LINES = 5

// 歌词行间距：相邻歌词行之间的纵向间距，独立于行内上下内边距（4.dp）单独可调，
// 避免行间距偏大导致歌词过于松散；用于视口高度、兜底行距与纵向布局三处一致换算
private val LYRIC_LINE_SPACING = 1.dp

// 窗口上下各多渲染的行数：滚动时新行已在窗口内、被移除的行已完全移出视口，
// 两者在同一坐标系整体平移，因此只会连续上移，不会出现边缘闪现或整块替换
private const val LYRIC_WINDOW_BUFFER = 2

// 换行滚动：时长与缓动参照自然滚动（先快后慢）；跨度大于该行数（拖动进度/切歌）直接定位
private const val LYRIC_SCROLL_DURATION_MS = 400
private const val LYRIC_SCROLL_MAX_STEP = 2

// 非当前行不透明度：弱化视觉存在感，随高亮进度平滑过渡
private const val LYRIC_INACTIVE_ALPHA = 0.55f

// 拖拽调进度：释放时与基准标识的对齐容差（行），超出则不跳转并回弹到拖拽前位置
private const val LYRIC_SCRUB_SNAP_ROWS = 0.35f

// 对齐释放后吸附到目标行的动画时长
private const val LYRIC_SCRUB_SNAP_MS = 220

// 对齐跳转后的短时保护窗：窗内本地进度自走、忽略控制器的旧位置，
// 避免跟随 Effect 在 seek 回报前把内容拉回拖拽前的行
private const val LYRIC_SCRUB_SEEK_GUARD_MS = 1000L

// 基准标识（两侧短横线）尺寸与淡入淡出时长
private const val LYRIC_SCRUB_MARKER_FADE_MS = 160
private val LYRIC_SCRUB_DASH_WIDTH = 22.dp
private val LYRIC_SCRUB_DASH_HEIGHT = 2.dp

// 上下边缘渐变覆盖的总行数（上下各半）：随可见行数换算比例，行数增减时淡出区间保持一致。
// 取 ≥1.5 行：当容器高度不足以容纳设定行数时，顶部溢出行被裁成一薄片，仅靠窄渐变/单点透明
// 仍会残留纯色细线，需让渐变区覆盖一整个溢出行，使薄片深陷透明区
private const val FADE_TOTAL_LINES = 1.6f

// 上下边缘淡出：按纵向透明度梯度对内容做 DstIn 蒙层，使上下行渐变消失。
// 边缘两端各保留一段完全透明区间，杜绝子像素级残影——行带 graphicsLayer 缩放、以小数 y 放置时，
// 字形/光晕可能仅以 ≤1dp 的细线越过边缘，单点透明 stop 只掩到精确边界，仍会残留原色细线
private fun Modifier.verticalFadeMask(fadeFraction: Float = 0.25f): Modifier = drawWithCache {
    // 完全透明保护带：fadeFraction 随可见行数缩放，覆盖边缘残影的像素宽度
    val edgeGuard = fadeFraction * 0.12f
    val brush = Brush.verticalGradient(
        colorStops = arrayOf(
            0.0f to Color.Transparent,
            edgeGuard to Color.Transparent,
            fadeFraction to Color.Black,
            1f - fadeFraction to Color.Black,
            1f - edgeGuard to Color.Transparent,
            1f to Color.Transparent,
        )
    )
    onDrawWithContent {
        val bounds = Rect(Offset.Zero, size)
        // 绘制级裁剪与蒙层放在同一图层：子层包边像素在进入图层前即被裁除，
        // 避免英文光晕/字形越过窗口边缘后以未蒙层原色残留
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
