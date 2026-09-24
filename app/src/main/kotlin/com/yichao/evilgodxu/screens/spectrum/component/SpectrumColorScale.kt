package com.yichao.evilgodxu.screens.spectrum.component

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.data.music.analysis.SPECTROGRAM_DYNAMIC_RANGE_DB

// 色条宽度与标签间距
private val BAR_WIDTH = 8.dp
private val LABEL_GAP = 4.dp

// 刻度档数：把量程等分，与动态范围的整十分档对齐；导出图按同一档数标注
internal const val SCALE_INTERVALS = 4

// dB 色标：竖直渐变条自上而下由满强度降至量程下限，右侧标注各档分贝值。
// 取色与频谱图同源，条上任意高度取到的颜色即图中同一强度对应的颜色
@Composable
internal fun SpectrumColorScale(modifier: Modifier = Modifier) {
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelStyle = remember(labelColor) {
        TextStyle(color = labelColor, fontSize = 11.sp)
    }
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val barWidthPx = with(density) { BAR_WIDTH.toPx() }
    val labelGapPx = with(density) { LABEL_GAP.toPx() }
    // 自上而下与色板相反：顶部为满强度，底部为量程下限
    val gradient = remember { SPECTRUM_COLOR_STOPS.map { Color(it) }.reversed() }
    Canvas(modifier = modifier) {
        drawRect(
            brush = Brush.verticalGradient(gradient, startY = 0f, endY = size.height),
            topLeft = Offset.Zero,
            size = Size(barWidthPx, size.height),
        )
        val height = size.height.coerceAtLeast(1f)
        for (step in 0..SCALE_INTERVALS) {
            val fraction = step.toFloat() / SCALE_INTERVALS
            val decibel = -SPECTROGRAM_DYNAMIC_RANGE_DB * fraction
            val layout = textMeasurer.measure(
                AnnotatedString(if (decibel == 0f) "0" else decibel.toInt().toString()),
                labelStyle,
            )
            // 首末刻度与色条端重合，标签向内收以免被容器裁掉
            val labelY = (height * fraction - layout.size.height / 2f)
                .coerceIn(0f, (height - layout.size.height).coerceAtLeast(0f))
            drawText(textLayoutResult = layout, topLeft = Offset(barWidthPx + labelGapPx, labelY))
        }
    }
}
