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

// 中间档位的步长：5kHz 一档，与常见频谱软件的读数习惯一致
private const val TICK_STEP_HZ = 5000

// 刻度线长度与标签间距
private val TICK_LINE_LENGTH = 6.dp
private val LABEL_GAP = 4.dp

// 频率刻度轴：竖轴底端为 0Hz、顶端为奈奎斯特频率，中间按固定步长分档。
// 刻度线按频率线性比例落在轴上（而非等分轴长），使读数与图中频带位置对得上
@Composable
internal fun SpectrumFrequencyAxis(
    nyquistHz: Int,
    modifier: Modifier = Modifier,
) {
    val ticks = remember(nyquistHz) { frequencyTicks(nyquistHz) }
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelStyle = remember(axisColor) {
        TextStyle(color = axisColor, fontSize = 11.sp)
    }
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val tickLinePx = with(density) { TICK_LINE_LENGTH.toPx() }
    val labelGapPx = with(density) { LABEL_GAP.toPx() }
    Canvas(modifier = modifier) {
        // 轴线贴容器右缘：右侧紧邻频谱图，刻度线向左伸出，标签再往左排
        val axisX = size.width - 1f
        drawLine(axisColor, Offset(axisX, 0f), Offset(axisX, size.height), strokeWidth = 1f)
        val height = size.height.coerceAtLeast(1f)
        ticks.forEach { tick ->
            val y = height * (1f - tick.hertz.toFloat() / nyquistHz)
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

// 频率刻度集合：底端 0，向上按步长递增，顶端恒为奈奎斯特频率；
// 距顶端不足半档的中间档位省略，避免与奈奎斯特标签挤在一起
private fun frequencyTicks(nyquistHz: Int): List<FrequencyTick> {
    val ticks = ArrayList<FrequencyTick>()
    ticks.add(FrequencyTick(0, "0 Hz"))
    var hertz = TICK_STEP_HZ
    while (hertz < nyquistHz - TICK_STEP_HZ / 2) {
        ticks.add(FrequencyTick(hertz, "${hertz / 1000}K"))
        hertz += TICK_STEP_HZ
    }
    ticks.add(FrequencyTick(nyquistHz, formatNyquist(nyquistHz)))
    return ticks
}

// 奈奎斯特刻度：整千按千赫兹取整，其余保留一位小数
private fun formatNyquist(hertz: Int): String =
    if (hertz % 1000 == 0) "${hertz / 1000} kHz" else "%.1f kHz".format(hertz / 1000f)

// 频率刻度项：位置由频率值换算，标签仅作展示
private data class FrequencyTick(val hertz: Int, val label: String)
