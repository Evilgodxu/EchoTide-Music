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

// 对齐方式到 Compose 布局参数的换算：整行、逐字、词分行三种渲染共用同一份口径，
// 避免各处各自判断对齐值而出现某条路径仍居中
internal val LyricAlignment.columnAlignment: Alignment.Horizontal
    get() = if (this == LyricAlignment.START) Alignment.Start else Alignment.CenterHorizontally

internal val LyricAlignment.rowArrangement: Arrangement.Horizontal
    get() = if (this == LyricAlignment.START) Arrangement.Start else Arrangement.Center

internal val LyricAlignment.lineTextAlign: TextAlign
    get() = if (this == LyricAlignment.START) TextAlign.Start else TextAlign.Center

// 歌词行动画缩放：普通行微缩，当前行高亮放大至 max；分行按预留上限 max 计算宽度
internal const val LYRIC_ROW_SCALE_BASE = 0.98f
internal const val LYRIC_ROW_SCALE_AMPLITUDE = 0.16f
internal const val LYRIC_ROW_SCALE_MAX = 1.35f

// 折行测量专用的固定字重：折行点只应由文本内容与可用宽度决定。
// 渲染字重随高亮态在 Medium/Normal 间切换，若折行也沿用渲染字重，同一行在高亮前后会量出不同的
// 折行点，表现为行被高亮放大时重新折行、行数跳变。固定字重后折行结果对高亮态不敏感，
// 放大余量由 LYRIC_ROW_SCALE_MAX 预留的宽度承担，故此处取基准字重即可
internal val LYRIC_WRAP_FONT_WEIGHT = FontWeight.Normal

// 普通歌词（无逐字时序）：按字均分时间整行顺序点亮，正在演唱的字高亮从左到右扫过并叠加弹簧跳动；
// 点亮时长留出行尾提前量，末字在切到下一行前完整亮满
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
    // 提前量恒小于行时长，按字均分后仍有正的每字时长
    val perCharMs = (lineDuration - lyricEndLeadMs(line, nextTimeMs)) / totalLen.toFloat()

    // 当前正在演唱的字下标：仅当前行且已开唱才计算，唱完时停在末字
    val currentCharIdx = if (isCurrent && positionMs > line.timeMs) {
        ((positionMs - line.timeMs).toFloat() / perCharMs).toInt().coerceIn(0, totalLen - 1)
    } else {
        -1
    }
    // 行内已播放时长折算成字符级进度：当前字的亮起比例由其与整数的差值决定
    val charProgress = if (isCurrent && positionMs > line.timeMs) {
        (positionMs - line.timeMs).toFloat() / perCharMs
    } else {
        -1f
    }

    // 逐字独立渲染无法借助 Text 软换行，按传入的可用宽度手动分行
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

// 单个歌词字：未唱为待唱色，已唱为高亮色；正在演唱的字高亮按 fillFraction 从左到右
// 逐渐亮起。待唱层与高亮层共用同一文本布局绘制，逐像素对齐，避免缩放跳起时错位
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
    // 跳起与落下均渐进过渡：新字柔和弹起的同时旧字缓缓回落，
    // 两者在时间上重叠，形成连续流动感，避免瞬间落下/瞬间跳起的突兀
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
    // 位置进度按采样周期跳跃推进，用线性 tween 平滑成连续亮起动画
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
    // 文本样式与 Text 组件默认行为一致（沿用 LocalTextStyle），保证布局高度准确
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
                // 跳起效果：弹簧放大带过冲 + 轻微上浮
                scaleX = 1f + 0.14f * emphasis.value
                scaleY = 1f + 0.14f * emphasis.value
                translationY = -floatPx * emphasis.value
            }
            .size(
                width = with(density) { layout.size.width.toDp() },
                height = with(density) { layout.size.height.toDp() },
            )
            .drawWithContent {
                // 待唱层：整字待唱色
                drawText(layout, color = pendingColor, topLeft = Offset.Zero)
                // 高亮层：按已亮起比例从左到右裁剪露出，发光随高亮区域显示
                clipRect(right = size.width * highlightFraction.coerceIn(0f, 1f)) {
                    drawText(layout, color = activeColor, shadow = glowShadow, topLeft = Offset.Zero)
                }
            }
    )
}

private const val LINE_CHAR_SHADOW_BLUR = 5f

private const val LYRIC_FILL_SMOOTH_MS = 60

// 演唱结束的字回落用时：与下一字跳起重叠渐变，形成渐落衔接，不宜过短
private const val LYRIC_JUMP_DOWN_MS = 320

// 按可用宽度分行：行宽超过可用宽度即换行，行尾截断完整单词时回退到最近空格
internal fun wrapLyricText(
    text: String,
    maxWidthPx: Int,
    textMeasurer: TextMeasurer,
    style: TextStyle,
): List<String> {
    if (text.isBlank() || maxWidthPx <= 0) return listOf(text)
    // 整行放得下时免去分行测量
    if (textMeasurer.measure(AnnotatedString(text), style).size.width <= maxWidthPx) return listOf(text)
    val rows = mutableListOf<String>()
    var lineStart = 0
    while (lineStart < text.length) {
        // 逐字扩展行前缀，找到首个超过可用宽度的位置
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
        // 行尾落在单词中间时回退到最近的空格断行，避免截断完整单词
        var breakAt = end
        var j = end
        while (j > lineStart) {
            if (text[j - 1].isWhitespace()) {
                breakAt = j
                break
            }
            j--
        }
        // 窄屏下至少放入一个字符，避免出现空行
        if (breakAt <= lineStart) breakAt = lineStart + 1
        rows.add(text.substring(lineStart, breakAt))
        lineStart = breakAt
        // 跳过下一行行首空白
        while (lineStart < text.length && text[lineStart].isWhitespace()) lineStart++
    }
    return rows
}

// 滚动过渡时长：下一行起点一到，当前行即停止居中并滚动退场（与歌词面板的跟随动画同源）。
// 点亮须在滚动开始前完成，否则会看到半亮的末字被滚走
internal const val LYRIC_SCROLL_TRANSITION_MS = 400

// 末字时长系数：歌词只给字起点、不给时长时，末字按平均字时长的该倍数估出收尾时刻，
// 略微留出尾音余量，而不是一路拉到下一行起点
private const val LYRIC_LAST_UNIT_DURATION_FACTOR = 1.2f

// 行尾提前量：整行（或逐字末词）点亮在切到下一行前完成的余量。
//
// 点亮终点取「末字自然收尾」与「下一行起点 − 滚动过渡时长」的较小者：
// 前者由末字起点加上平均字时长乘系数估得，让末字按自己的节奏完整亮起；
// 后者保证滚动开始时整行已经亮满。下一行起点是硬边界，终点绝不越过它。
// 各处歌词渲染共用本函数，使整行与逐字两种口径、迷你条与完整播放器的末尾行为一致
internal fun lyricEndLeadMs(line: LyricLine, nextTimeMs: Long): Long {
    val lineDuration = (nextTimeMs - line.timeMs).coerceAtLeast(1L)
    // 行短于两倍过渡时长时余量至多占半行，避免把整行点亮压成一瞬
    val scrollLeadMs = minOf(LYRIC_SCROLL_TRANSITION_MS.toLong(), lineDuration / 2)
    val fillEndMs = minOf(lyricNaturalEndMs(line, nextTimeMs), nextTimeMs - scrollLeadMs)
    // 提前量恒小于行时长，按字/按词均分后仍有正的点亮时长
    return (nextTimeMs - fillEndMs).coerceIn(0L, lineDuration - 1L)
}

// 末字自然收尾时刻：有时长按源给的终点；只有字起点时按末词之前各字的平均字时长估出末字时长，
// 不再把它拉长到下一行起点。无逐字时序时按字均分整行，末字正好唱到行尾
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
        // 整行只有一个词时没有更早的字可参照，按整行均分
        (nextTimeMs - line.timeMs).toFloat() / lastChars
    }
    return lastWord.startMs +
        (lastChars * avgCharMs * LYRIC_LAST_UNIT_DURATION_FACTOR).roundToLong()
}

// 词终点：优先取词自身的时长；增强 LRC 只提供起点（duration 为 0）时用下一个词的起点兜底，
// 末词用下一行起点兜底，保证每个词都有可用的起止区间。
// [tailLeadMs] 为末词的行尾提前量：逐字时序常把末字延伸到句尾，末词终点因而正落在切行时刻上，
// 整行高亮要到切到下一行那一刻才刚完成。传入正值即把末词终点提前该值，使最后一个词在切行前
// 完整点亮；末词自身区间容不下提前量时保持原终点，避免把整词压成瞬间点亮
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
    // 末词起点已越过提前后的终点（贴近行尾的短词）时保持原终点，不硬挤出提前量
    return if (ends[lastIndex] > leadEnd && leadEnd > words[lastIndex].startMs) {
        ends.toMutableList().also { it[lastIndex] = leadEnd }
    } else {
        ends
    }
}

// 逐字歌词的行内点亮比例：已唱完的词按整词字数计入，正在演唱的词按词内已过时长折算到字数，
// 返回整行已点亮字符占比。总字数按词文本累计，与 [LyricLine.text] 同源，故该比例可直接
// 折算成文字宽度上的点亮边缘；词起点与时长都不均匀（词间可能存在空隙），
// 因此不做「整行时长按词数均分」的近似
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
        // 至少留 1ms 区间：零时长词不能除零，也不能整词瞬间跳过
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

// 逐字歌词：严格按每个词自身的起止时间做卡拉OK式点亮——已唱完的词整词高亮，
// 正在演唱的词内逐字从左到右亮起并叠加弹簧跳动。词时序来自歌词源，起点与时长都不均匀
// （词间可能存在空隙），故不做「整行时长按词数均分」的近似；末词终点另按行尾提前量收窄，
// 与无词时序时的按字均分口径一致，最后一个词同样在切到下一行前完整点亮
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
    // 词终点与迷你条歌词共用同一套换算，两处点亮时序一致
    val wordEnds = remember(line.words, nextTimeMs, line.timeMs) {
        lyricWordEnds(line.words, nextTimeMs, tailLeadMs = lyricEndLeadMs(line, nextTimeMs))
    }

    // 词独立渲染无法借助 Text 软换行，按传入的可用宽度将整行词分成多行：英文词保持完整不截断
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = alignment.columnAlignment,
    ) {
        val textMeasurer = rememberTextMeasurer()
        // 与按字分行同口径：折行量宽用固定字重，不随当前行加粗而改变折行点
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
                    // 至少留 1ms 区间：零时长词不能除零，也不能整词瞬间跳过
                    val wordEnd = wordEnds[index].coerceAtLeast(wordStart + 1)
                    val isWordSung = isCurrent && positionMs >= wordEnd
                    val isWordCurrent = isCurrent && positionMs >= wordStart && positionMs < wordEnd
                    val charCount = word.text.length.coerceAtLeast(1)
                    // 当前词内已演唱的字符级进度：按词内已过时长占词时长比例折算到字数
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
                    // 词保持整体排版，词内逐字渲染以支持单字亮起与跳动；词间间距由词文本自带空格保留
                    Row(
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        word.text.forEachIndexed { charIdx, ch ->
                            // 仅当前演唱词中当前正在演唱的字触发跳动，其余字保持静态
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

// 按可用宽度分配逐字歌词的词：累加词宽超过宽度上限时换行，单个词保持完整不截断
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
        // 当前行加不下下一个词时提前换行；词本身超宽时强制独占一行
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
