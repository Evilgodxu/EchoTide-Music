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

// 刻度标签的 1-2-5 系列倍数：等频程内的三档取值，与常见频谱软件的读数习惯一致
private val TICK_MULTIPLIERS = intArrayOf(1, 2, 5)

// 1-2-5 系列的十进档上限：覆盖到 100kHz，超出人耳与常见采样率的讨论范围
private const val MAX_TICK_DECADE_HZ = 100_000

// 刻度线长度与标签间距
private val TICK_LINE_LENGTH = 6.dp
private val LABEL_GAP = 4.dp

// 相邻档位的最小间距：小于该值的中间档位省略，避免对数轴上的高频档位互相叠压
private val MIN_TICK_GAP = 16.dp

// 频率刻度轴：竖轴底端为量程下限、顶端为奈奎斯特频率，中间按 1-2-5 系列分档。
// 刻度线位置由传入的对数刻度换算，与图内频带共用同一套纵向坐标，读数与图上一一对齐。
// 容器纵向范围即绘图区高度（由 SpectrumChart 的布局保证），换算出的位置才与图一致
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
        // 轴线贴容器右缘：右侧紧邻频谱图，刻度线向左伸出，标签再往左排
        val axisX = size.width - 1f
        drawLine(axisColor, Offset(axisX, 0f), Offset(axisX, size.height), strokeWidth = 1f)
        val height = size.height.coerceAtLeast(1f)
        layoutFrequencyTicks(ticks, scale, height, minTickGapPx).forEach { (tick, y) ->
            drawLine(axisColor, Offset(axisX - tickLinePx, y), Offset(axisX, y), strokeWidth = 1f)
            val layout = textMeasurer.measure(AnnotatedString(tick.label), labelStyle)
            // 首末刻度与轴端重合，标签向内收以免被容器裁掉
            val labelY = (y - layout.size.height / 2f)
                .coerceIn(0f, (height - layout.size.height).coerceAtLeast(0f))
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(axisX - tickLinePx - labelGapPx - layout.size.width, labelY),
            )
        }
    }
}

// 频率刻度项：位置由频率值经轴刻度换算，标签仅作展示
internal class FrequencyTick(val hertz: Float, val label: String)

// 频率刻度候选：1-2-5 系列落在轴量程内的取值，顶端恒为奈奎斯特频率。
// 档位给满，实际是否绘制由 layoutFrequencyTicks 的间距过滤决定
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

// 按最小间距挑选实际绘制的档位：自顶向下推进，顶端奈奎斯特刻度优先纳入，
// 与上一档距离不足的档位省略。返回各档位自绘图区顶端的纵向像素位置；
// 频率轴与导出图共用同一套挑选规则，两处刻度密度因此一致
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

// 刻度标签：1kHz 以下按赫兹、整千按整数千赫兹，其余保留一位小数（奈奎斯特常带小数）
private fun formatHertz(hertz: Float): String = when {
    hertz < 1000f -> "${hertz.toInt()} Hz"
    hertz % 1000f == 0f -> "${(hertz / 1000f).toInt()} kHz"
    else -> "%.1f kHz".format(hertz / 1000f)
}
