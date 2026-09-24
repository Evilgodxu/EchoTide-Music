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

// 频谱图与左侧刻度列的间距
private val IMAGE_START_GAP = 4.dp

// 色标与频谱图的间距
private val COLOR_SCALE_GAP = 8.dp

// 时间刻度行与频谱图的间距
private val TIME_LABEL_GAP = 4.dp

// 频谱图：绘图区一行内自左向右为频率刻度、时频图、dB 色标，三者等高且共用同一套纵向坐标
// （换算见 FrequencyAxisScale），刻度线与图内频带据此一一对齐；
// 时间刻度另起一行置于绘图区下方，左右两端按同一组间距常量与图的横向范围对齐。
// 轴的朝向固定，图的形状随容器伸缩
@Composable
internal fun SpectrumChart(
    spectrogram: Spectrogram,
    durationMs: Long,
    modifier: Modifier = Modifier,
) {
    // 频率刻度：量程上限为奈奎斯特频率，左轴与图共用同一实例换算纵向位置
    val scale = remember(spectrogram.sampleRate) {
        FrequencyAxisScale.ofSampleRate(spectrogram.sampleRate)
    }
    val timeLabels = remember(durationMs) { listOf(formatTime(0L), formatTime(durationMs)) }
    Column(modifier = modifier) {
        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
            SpectrumFrequencyAxis(
                scale = scale,
                modifier = Modifier.width(FREQUENCY_AXIS_WIDTH).fillMaxHeight(),
            )
            SpectrogramImage(
                spectrogram = spectrogram,
                scale = scale,
                modifier = Modifier.weight(1f).fillMaxHeight().padding(start = IMAGE_START_GAP),
            )
            SpectrumColorScale(
                modifier = Modifier
                    .width(COLOR_SCALE_WIDTH)
                    .fillMaxHeight()
                    .padding(start = COLOR_SCALE_GAP),
            )
        }
        // 两端留白按图的横向范围取：左侧是刻度列宽加图的起始间距，右侧是色标列宽
        // （色标列的间距在其内部，图的右缘即该列左缘，故右侧不再叠加）
        TimeLabels(
            labels = timeLabels,
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    top = TIME_LABEL_GAP,
                    start = FREQUENCY_AXIS_WIDTH + IMAGE_START_GAP,
                    end = COLOR_SCALE_WIDTH,
                ),
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
