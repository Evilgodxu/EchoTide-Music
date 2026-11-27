package com.yichao.evilgodxu.screens.spectrum.component

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.data.music.analysis.Spectrogram
import com.yichao.evilgodxu.utils.formatTime

// 刻度列宽 58dp
private val FREQUENCY_AXIS_WIDTH = 58.dp

// 色标列宽 50dp
private val COLOR_SCALE_WIDTH = 50.dp

// 频谱图与左侧刻度列的间距
private val IMAGE_START_GAP = 4.dp

// 色标与频谱图的间距
private val COLOR_SCALE_GAP = 8.dp

// 时间刻度行与频谱图的间距
private val TIME_LABEL_GAP = 4.dp

// 一行内为刻度、时频图、色标，共用纵向坐标
@Composable
internal fun SpectrumChart(
    spectrogram: Spectrogram,
    durationMs: Long,
    onShareImage: () -> Unit,
    onSaveImage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 量程上限为奈奎斯特频率
    val scale = remember(spectrogram.sampleRate) {
        FrequencyAxisScale.ofSampleRate(spectrogram.sampleRate)
    }
    val timeLabels = remember(durationMs) { listOf(formatTime(0L), formatTime(durationMs)) }
    var menuVisible by remember { mutableStateOf(false) }
    Box(
        modifier = modifier.combinedClickable(
            onClick = {},
            onLongClick = { menuVisible = true },
        ),
    ) {
        Column(Modifier.fillMaxSize()) {
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
            // 两端留白按图的横向范围取
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
        SpectrumContextMenu(
            visible = menuVisible,
            onShare = {
                menuVisible = false
                onShareImage()
            },
            onSave = {
                menuVisible = false
                onSaveImage()
            },
            onDismiss = { menuVisible = false },
        )
    }
}

// 时间刻度：两端标起点与总时长
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
