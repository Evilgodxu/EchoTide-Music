package com.yichao.evilgodxu.ui.component.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import com.yichao.evilgodxu.data.music.model.LyricLine
import com.yichao.evilgodxu.data.music.model.LyricWord
import com.yichao.evilgodxu.data.settings.LyricAlignment
import kotlin.math.roundToLong

// 对齐方式统一换算，三条渲染路径共用
internal val LyricAlignment.columnAlignment: Alignment.Horizontal
    get() = if (this == LyricAlignment.START) Alignment.Start else Alignment.CenterHorizontally

internal val LyricAlignment.rowArrangement: Arrangement.Horizontal
    get() = if (this == LyricAlignment.START) Arrangement.Start else Arrangement.Center

internal val LyricAlignment.lineTextAlign: TextAlign
    get() = if (this == LyricAlignment.START) TextAlign.Start else TextAlign.Center

// 行缩放：基准 0.98，当前行最高 1.35
internal const val LYRIC_ROW_SCALE_BASE = 0.98f
internal const val LYRIC_ROW_SCALE_AMPLITUDE = 0.16f
internal const val LYRIC_ROW_SCALE_MAX = 1.35f

// 折行用固定字重，避免高亮时折行点跳变
internal val LYRIC_WRAP_FONT_WEIGHT = FontWeight.Normal

// 无逐字时序：按字均分点亮，行尾留提前量
@Composable
internal fun LineFillLyricText(
    line: LyricLine,
    nextTimeMs: Long,
    positionMs: Long,
    isCurrent: Boolean,
    fontSize: TextUnit,
    fontWeight: FontWeight,
    activeColor: Color,
    pendingColor: Color,
    widthPx: Int,
    alignment: LyricAlignment,
    modifier: Modifier = Modifier,
) {
    val lineDuration = (nextTimeMs - line.timeMs).coerceAtLeast(1L)
    val totalLen = line.text.length.coerceAtLeast(1)
    // 提前量小于行时长，每字时长仍为正
    val perCharMs = (lineDuration - lyricEndLeadMs(line, nextTimeMs)) / totalLen.toFloat()

    // 当前演唱的字下标，未开唱为 -1
    val currentCharIdx = if (isCurrent && positionMs > line.timeMs) {
        ((positionMs - line.timeMs).toFloat() / perCharMs).toInt().coerceIn(0, totalLen - 1)
    } else {
        -1
    }
    // 行内已播时长折算为字符级进度
    val charProgress = if (isCurrent && positionMs > line.timeMs) {
        (positionMs - line.timeMs).toFloat() / perCharMs
    } else {
        -1f
    }

    // 逐字渲染无法软换行，按宽度手动分行
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = alignment.columnAlignment,
    ) {
        val textMeasurer = rememberTextMeasurer()
        val wrapStyle = LocalTextStyle.current.merge(
            TextStyle(fontSize = fontSize, fontWeight = LYRIC_WRAP_FONT_WEIGHT),
        )
        val rows = remember(line.text, widthPx, wrapStyle) {
            if (widthPx <= 0) listOf(line.text) else wrapLyricText(line.text, widthPx, textMeasurer, wrapStyle)
        }
        var globalIdx = 0
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = alignment.rowArrangement,
            ) {
                row.forEach { ch ->
                    val idx = globalIdx++
                    LyricChar(
                        text = ch.toString(),
                        fillFraction = if (charProgress < 0f) 0f else (charProgress - idx).coerceIn(0f, 1f),
                        filling = isCurrent && idx == currentCharIdx,
                        fontSize = fontSize,
                        fontWeight = fontWeight,
                        activeColor = activeColor,
                        pendingColor = pendingColor,
                        shadowBlurRadius = LINE_CHAR_SHADOW_BLUR,
                    )
                }
            }
        }
    }
}

// 单字：待唱层与高亮层共用布局，逐像素对齐
@Composable
internal fun LyricChar(
    text: String,
    fillFraction: Float,
    filling: Boolean,
    fontSize: TextUnit,
    fontWeight: FontWeight,
    activeColor: Color,
    pendingColor: Color,
    shadowBlurRadius: Float,
) {
    // 跳起与回落重叠过渡，形成流动感
    val emphasis = remember { Animatable(0f) }
    LaunchedEffect(filling) {
        emphasis.animateTo(
            targetValue = if (filling) 1f else 0f,
            animationSpec = if (filling) {
                spring(
                    dampingRatio = 0.55f,
                    stiffness = 420f,
                )
            } else {
                tween(
                    durationMillis = LYRIC_JUMP_DOWN_MS,
                    easing = LinearOutSlowInEasing,
                )
            },
        )
    }
    // 线性 tween 把跳跃进度平滑成连续亮起
    val highlightFraction by animateFloatAsState(
        targetValue = fillFraction,
        animationSpec = tween(
            durationMillis = LYRIC_FILL_SMOOTH_MS,
            easing = LinearEasing,
        ),
        label = "lyric_char_fill",
    )
    val density = LocalDensity.current
    val floatPx = with(density) { 0.05f * fontSize.toPx() }
    // 样式沿用 LocalTextStyle，保证行高准确
    val textMeasurer = rememberTextMeasurer()
    val currentTextStyle = LocalTextStyle.current
    val layout = remember(text, fontSize, fontWeight, currentTextStyle) {
        textMeasurer.measure(
            AnnotatedString(text),
            currentTextStyle.merge(
                TextStyle(fontSize = fontSize, fontWeight = fontWeight)
            ),
        )
    }
    val glowShadow = Shadow(activeColor.copy(alpha = 0.65f), blurRadius = shadowBlurRadius)
    Box(
        modifier = Modifier
            .graphicsLayer {
                // 弹簧放大带过冲并轻微上浮
                scaleX = 1f + 0.14f * emphasis.value
                scaleY = 1f + 0.14f * emphasis.value
                translationY = -floatPx * emphasis.value
            }
            .size(
                width = with(density) { layout.size.width.toDp() },
                height = with(density) { layout.size.height.toDp() },
            )
            .drawWithContent {
                // 待唱层整字铺待唱色
                drawText(layout, color = pendingColor, topLeft = Offset.Zero)
                // 高亮层按已亮比例裁出，发光随之显示
                clipRect(right = size.width * highlightFraction.coerceIn(0f, 1f)) {
                    drawText(layout, color = activeColor, shadow = glowShadow, topLeft = Offset.Zero)
                }
            }
    )
}

private const val LINE_CHAR_SHADOW_BLUR = 5f

// 逐字填充只补一帧的差：位置已按 vsync 推进，过渡再长就会持续落后于真实进度
private const val LYRIC_FILL_SMOOTH_MS = 16

// 回落 320ms，与下一字跳起重叠
private const val LYRIC_JUMP_DOWN_MS = 320

// 按可用宽度分行，行尾回退到最近空格
internal fun wrapLyricText(
    text: String,
    maxWidthPx: Int,
    textMeasurer: TextMeasurer,
    style: TextStyle,
): List<String> {
    if (text.isBlank() || maxWidthPx <= 0) return listOf(text)
    // 整行放得下免测量
    if (textMeasurer.measure(AnnotatedString(text), style).size.width <= maxWidthPx) return listOf(text)
    val rows = mutableListOf<String>()
    var lineStart = 0
    while (lineStart < text.length) {
        // 逐字扩展找到首个超宽位置
        var end = lineStart
        while (end < text.length && textMeasurer.measure(
                AnnotatedString(text.substring(lineStart, end + 1)), style,
            ).size.width <= maxWidthPx
        ) {
            end++
        }
        if (end >= text.length) {
            rows.add(text.substring(lineStart))
            break
        }
        // 行尾在词中时回退到最近空格
        var breakAt = end
        var j = end
        while (j > lineStart) {
            if (text[j - 1].isWhitespace()) {
                breakAt = j
                break
            }
            j--
        }
        // 至少放入一个字符，避免空行
        if (breakAt <= lineStart) breakAt = lineStart + 1
        rows.add(text.substring(lineStart, breakAt))
        lineStart = breakAt
        // 跳过下一行行首空白
        while (lineStart < text.length && text[lineStart].isWhitespace()) lineStart++
    }
    return rows
}

// 滚动过渡 400ms，点亮须先完成
internal const val LYRIC_SCROLL_TRANSITION_MS = 400

// 无时长时末字按平均字时长的 1.2 倍估收尾
private const val LYRIC_LAST_UNIT_DURATION_FACTOR = 1.2f

// 行尾提前量：取末字收尾与滚动前的较小者
internal fun lyricEndLeadMs(line: LyricLine, nextTimeMs: Long): Long {
    val lineDuration = (nextTimeMs - line.timeMs).coerceAtLeast(1L)
    // 行短于两倍过渡时长时余量至多占半行
    val scrollLeadMs = minOf(LYRIC_SCROLL_TRANSITION_MS.toLong(), lineDuration / 2)
    val fillEndMs = minOf(lyricNaturalEndMs(line, nextTimeMs), nextTimeMs - scrollLeadMs)
    // 提前量恒小于行时长，点亮时长仍为正
    return (nextTimeMs - fillEndMs).coerceIn(0L, lineDuration - 1L)
}

// 末字自然收尾：有时长用源终点，否则按平均字时长估
private fun lyricNaturalEndMs(line: LyricLine, nextTimeMs: Long): Long {
    val words = line.words
    if (words.isEmpty()) return nextTimeMs
    val lastWord = words.last()
    if (lastWord.durationMs > 0L) return lastWord.startMs + lastWord.durationMs
    val lastChars = lastWord.text.length.coerceAtLeast(1)
    val leadingChars = words.sumOf { it.text.length } - lastChars
    val avgCharMs = if (leadingChars > 0) {
        (lastWord.startMs - line.timeMs).toFloat() / leadingChars
    } else {
        // 整行只有一个词时按整行均分
        (nextTimeMs - line.timeMs).toFloat() / lastChars
    }
    return lastWord.startMs +
        (lastChars * avgCharMs * LYRIC_LAST_UNIT_DURATION_FACTOR).roundToLong()
}

// 词终点：优先用词时长，否则用下一词起点，末词用行起点
// tailLeadMs 把末词终点提前，使其在切行前亮完
internal fun lyricWordEnds(
    words: List<LyricWord>,
    nextTimeMs: Long,
    tailLeadMs: Long = 0L,
): List<Long> {
    val ends = words.mapIndexed { index, word ->
        if (word.durationMs > 0) {
            word.startMs + word.durationMs
        } else {
            words.getOrNull(index + 1)?.startMs ?: nextTimeMs
        }
    }
    val lastIndex = ends.lastIndex
    if (tailLeadMs <= 0L || lastIndex < 0) return ends
    val leadEnd = nextTimeMs - tailLeadMs
    // 短词贴近行尾时保持原终点，不硬挤提前量
    return if (ends[lastIndex] > leadEnd && leadEnd > words[lastIndex].startMs) {
        ends.toMutableList().also { it[lastIndex] = leadEnd }
    } else {
        ends
    }
}

// 整行已点亮字符占比，按词内时长折算
internal fun lyricWordFillFraction(
    words: List<LyricWord>,
    wordEnds: List<Long>,
    positionMs: Long,
): Float {
    val totalChars = words.sumOf { it.text.length }
    if (totalChars <= 0) return 0f
    var filledChars = 0f
    words.forEachIndexed { index, word ->
        val start = word.startMs
        // 至少 1ms 区间，零时长词不除零
        val end = wordEnds[index].coerceAtLeast(start + 1)
        val charCount = word.text.length
        filledChars += when {
            positionMs >= end -> charCount.toFloat()
            positionMs >= start -> (positionMs - start).toFloat() / (end - start) * charCount
            else -> 0f
        }
    }
    return (filledChars / totalChars).coerceIn(0f, 1f)
}

// 逐字歌词：按词起止做卡拉OK式点亮，末词按行尾提前量收窄
@Composable
internal fun WordSplitLyricText(
    line: LyricLine,
    nextTimeMs: Long,
    positionMs: Long,
    isCurrent: Boolean,
    fontSize: TextUnit,
    fontWeight: FontWeight,
    activeColor: Color,
    pendingColor: Color,
    widthPx: Int,
    alignment: LyricAlignment,
    modifier: Modifier = Modifier,
) {
    // 词终点与迷你条共用同一换算
    val wordEnds = remember(line.words, nextTimeMs, line.timeMs) {
        lyricWordEnds(line.words, nextTimeMs, tailLeadMs = lyricEndLeadMs(line, nextTimeMs))
    }

    // 按宽度分行，英文词保持完整
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = alignment.columnAlignment,
    ) {
        val textMeasurer = rememberTextMeasurer()
        // 折行量宽用固定字重
        val wrapStyle = LocalTextStyle.current.merge(
            TextStyle(fontSize = fontSize, fontWeight = LYRIC_WRAP_FONT_WEIGHT),
        )
        val rows = remember(line.words, widthPx, wrapStyle) {
            if (widthPx <= 0) listOf(line.words) else wrapLyricWords(line.words, widthPx, textMeasurer, wrapStyle)
        }
        var globalIdx = 0
        rows.forEach { rowWords ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = alignment.rowArrangement,
            ) {
                rowWords.forEach { word ->
                    val index = globalIdx++
                    val wordStart = word.startMs
                    // 至少 1ms 区间，零时长词不除零
                    val wordEnd = wordEnds[index].coerceAtLeast(wordStart + 1)
                    val isWordSung = isCurrent && positionMs >= wordEnd
                    val isWordCurrent = isCurrent && positionMs >= wordStart && positionMs < wordEnd
                    val charCount = word.text.length.coerceAtLeast(1)
                    // 当前词内已唱字符进度
                    val wordCharProgress = if (isWordCurrent) {
                        (positionMs - wordStart).toFloat() / (wordEnd - wordStart) * charCount
                    } else {
                        0f
                    }
                    val currentCharIdx = if (isWordCurrent) {
                        wordCharProgress.toInt().coerceIn(0, charCount - 1)
                    } else {
                        -1
                    }
                    // 词内逐字渲染，词间空格随词文本保留
                    Row(
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        word.text.forEachIndexed { charIdx, ch ->
                            // 仅当前演唱字的当前字触发跳动
                            val isFilling = isWordCurrent && charIdx == currentCharIdx
                            LyricChar(
                                text = ch.toString(),
                                fillFraction = when {
                                    isWordSung -> 1f
                                    isWordCurrent -> (wordCharProgress - charIdx).coerceIn(0f, 1f)
                                    else -> 0f
                                },
                                filling = isFilling,
                                fontSize = fontSize,
                                fontWeight = fontWeight,
                                activeColor = activeColor,
                                pendingColor = pendingColor,
                                shadowBlurRadius = WORD_CHAR_SHADOW_BLUR,
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val WORD_CHAR_SHADOW_BLUR = 7f

// 按宽度分配词，词保持完整
private fun wrapLyricWords(
    words: List<LyricWord>,
    maxWidthPx: Int,
    textMeasurer: TextMeasurer,
    style: TextStyle,
): List<List<LyricWord>> {
    val rows = mutableListOf<List<LyricWord>>()
    val row = mutableListOf<LyricWord>()
    var rowWidth = 0
    words.forEach { word ->
        val wordWidth = textMeasurer.measure(AnnotatedString(word.text), style).size.width
        // 放不下则换行，超宽词独占一行
        if (row.isNotEmpty() && rowWidth + wordWidth > maxWidthPx) {
            rows.add(row.toList())
            row.clear()
            rowWidth = 0
        }
        row.add(word)
        rowWidth += wordWidth
    }
    if (row.isNotEmpty()) rows.add(row)
    return rows
}
