package com.yichao.evilgodxu.screens.spectrum.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.data.music.analysis.Spectrogram
import com.yichao.evilgodxu.utils.formatTime

// 频谱图：时频图与两条坐标轴构成一体——时间轴、频率轴分居图的相邻两边，
// 两条边的位置随朝向对调，使时间轴始终沿屏幕长边延伸、频率轴沿短边标注
@Composable
internal fun SpectrumChart(
    spectrogram: Spectrogram,
    durationMs: Long,
    vertical: Boolean,
    modifier: Modifier = Modifier,
) {
    // 刻度文案随量程变化才重建：避免每次重组都分配新列表并带着坐标轴一起重组
    val timeLabels = remember(durationMs) { listOf(formatTime(0L), formatTime(durationMs)) }
    // 频率轴自下而上标注，与图中低频贴底、高频在顶的排布一致
    val frequencyLabels = remember(spectrogram.sampleRate) {
        listOf(formatFrequency(spectrogram.sampleRate / 2), formatFrequency(0))
    }
    Row(modifier = modifier) {
        AxisLabels(
            labels = if (vertical) timeLabels else frequencyLabels,
            vertical = true,
            modifier = Modifier.fillMaxHeight().padding(end = 6.dp),
        )
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            SpectrogramImage(
                spectrogram = spectrogram,
                vertical = vertical,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            AxisLabels(
                labels = if (vertical) frequencyLabels else timeLabels,
                vertical = false,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
    }
}

// 坐标轴刻度：垂直轴成列、水平轴成行，两端标注该轴的量程边界
@Composable
private fun AxisLabels(
    labels: List<String>,
    vertical: Boolean,
    modifier: Modifier = Modifier,
) {
    if (vertical) {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.Start,
        ) {
            labels.forEach { TickLabel(it) }
        }
    } else {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            labels.forEach { TickLabel(it) }
        }
    }
}

// 刻度文字：小号次要色，不与时频图争夺注意力
@Composable
private fun TickLabel(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 11.sp,
    )
}

// 频率刻度：千赫兹以下按赫兹、以上按千赫兹保留一位小数
private fun formatFrequency(hertz: Int): String =
    if (hertz < 1000) "${hertz}Hz" else "%.1fkHz".format(hertz / 1000f)
