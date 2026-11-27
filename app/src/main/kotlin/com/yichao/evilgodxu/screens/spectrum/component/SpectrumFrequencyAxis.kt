package com.yichao.evilgodxu.screens.spectrum.component

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 1-2-5 系列倍数
private val TICK_MULTIPLIERS = intArrayOf(1, 2, 5)

// 十进档上限 100kHz
private const val MAX_TICK_DECADE_HZ = 100_000

// 刻度线长度与标签间距
private val TICK_LINE_LENGTH = 6.dp
private val LABEL_GAP = 4.dp

// 最小间距 16dp，避免高频叠压
private val MIN_TICK_GAP = 16.dp

// 频率轴：贴底为下限、贴顶为奈奎斯特，与图共用坐标
@Composable
internal fun SpectrumFrequencyAxis(
    scale: FrequencyAxisScale,
    modifier: Modifier = Modifier,
) {
    val ticks = remember(scale) { frequencyTicks(scale) }
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelStyle = remember(axisColor) {
        TextStyle(color = axisColor, fontSize = 11.sp)
    }
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val tickLinePx = with(density) { TICK_LINE_LENGTH.toPx() }
    val labelGapPx = with(density) { LABEL_GAP.toPx() }
    val minTickGapPx = with(density) { MIN_TICK_GAP.toPx() }
    Canvas(modifier = modifier) {
        // 轴线贴右缘，刻度向左延伸
        val axisX = size.width - 1f
        drawLine(axisColor, Offset(axisX, 0f), Offset(axisX, size.height), strokeWidth = 1f)
        val height = size.height.coerceAtLeast(1f)
        layoutFrequencyTicks(ticks, scale, height, minTickGapPx).forEach { (tick, y) ->
            drawLine(axisColor, Offset(axisX - tickLinePx, y), Offset(axisX, y), strokeWidth = 1f)
            val layout = textMeasurer.measure(AnnotatedString(tick.label), labelStyle)
            // 首末标签向内收，避免被裁
            val labelY = (y - layout.size.height / 2f)
                .coerceIn(0f, (height - layout.size.height).coerceAtLeast(0f))
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(axisX - tickLinePx - labelGapPx - layout.size.width, labelY),
            )
        }
    }
}

// 刻度项：位置由频率换算
internal class FrequencyTick(val hertz: Float, val label: String)

// 候选刻度：1-2-5 取值 + 奈奎斯特，间距另行过滤
internal fun frequencyTicks(scale: FrequencyAxisScale): List<FrequencyTick> {
    val ticks = ArrayList<FrequencyTick>()
    var decade = 1
    while (decade <= MAX_TICK_DECADE_HZ) {
        for (multiplier in TICK_MULTIPLIERS) {
            val hertz = decade * multiplier
            if (hertz >= scale.minHz && hertz < scale.maxHz) {
                ticks.add(FrequencyTick(hertz.toFloat(), formatHertz(hertz.toFloat())))
            }
        }
        decade *= 10
    }
    ticks.add(FrequencyTick(scale.maxHz, formatHertz(scale.maxHz)))
    return ticks
}

// 自顶向下挑选，间距不足则省略
internal fun layoutFrequencyTicks(
    ticks: List<FrequencyTick>,
    scale: FrequencyAxisScale,
    plotHeightPx: Float,
    minGapPx: Float,
): List<Pair<FrequencyTick, Float>> {
    val laidOut = ArrayList<Pair<FrequencyTick, Float>>(ticks.size)
    var lastY = -Float.MAX_VALUE
    ticks.asReversed().forEach { tick ->
        val y = plotHeightPx * (1f - scale.fractionOf(tick.hertz))
        if (y - lastY < minGapPx) return@forEach
        lastY = y
        laidOut.add(tick to y)
    }
    return laidOut
}

// <1kHz 用 Hz，整千用整数 kHz，否则一位小数
private fun formatHertz(hertz: Float): String = when {
    hertz < 1000f -> "${hertz.toInt()} Hz"
    hertz % 1000f == 0f -> "${(hertz / 1000f).toInt()} kHz"
    else -> "%.1f kHz".format(hertz / 1000f)
}
