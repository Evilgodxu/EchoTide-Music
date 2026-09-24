package com.yichao.evilgodxu.screens.spectrum.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.data.music.analysis.Spectrogram
import com.yichao.evilgodxu.utils.formatTime

// 频率刻度列宽：容纳奈奎斯特标签与刻度线
private val FREQUENCY_AXIS_WIDTH = 58.dp

// 色标列宽：色条 + 分贝标签
private val COLOR_SCALE_WIDTH = 50.dp

// 频谱图：左列频率刻度、右列 dB 色标、图下方时间刻度，
// 三条信息各占一边而不与图重叠，把可用空间尽量留给图本身。
// 轴的朝向固定，图的形状随容器伸缩
@Composable
internal fun SpectrumChart(
    spectrogram: Spectrogram,
    durationMs: Long,
    modifier: Modifier = Modifier,
) {
    val timeLabels = remember(durationMs) { listOf(formatTime(0L), formatTime(durationMs)) }
    Row(modifier = modifier) {
        SpectrumFrequencyAxis(
            nyquistHz = spectrogram.sampleRate / 2,
            modifier = Modifier.width(FREQUENCY_AXIS_WIDTH).fillMaxHeight(),
        )
        Column(modifier = Modifier.weight(1f).fillMaxHeight().padding(start = 4.dp)) {
            SpectrogramImage(
                spectrogram = spectrogram,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            TimeLabels(labels = timeLabels, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        }
        SpectrumColorScale(
            modifier = Modifier.width(COLOR_SCALE_WIDTH).fillMaxHeight().padding(start = 8.dp),
        )
    }
}

// 时间刻度：沿图的水平方向两端标注起点与总时长
@Composable
private fun TimeLabels(labels: List<String>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        labels.forEach { label ->
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                maxLines = 1,
            )
        }
    }
}
