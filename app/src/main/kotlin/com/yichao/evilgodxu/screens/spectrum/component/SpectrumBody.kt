package com.yichao.evilgodxu.screens.spectrum.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yichao.evilgodxu.screens.spectrum.SpectrumUiState
import com.yichao.evilgodxu.screens.spectrum.hasVerdict

// 频谱内容：上半区按时频分析状态分发——分析中报进度、有结果则铺开时频图与两侧刻度，
// 否则给出不可分析占位；下半区依次是源文件参数行与曲目判定结论。
// 底部两块常驻，分析未完成时结论显示校验中，使该区域不会先空后跳
@Composable
internal fun SpectrumBody(
    uiState: SpectrumUiState,
    modifier: Modifier = Modifier,
) {
    val spectrogram = uiState.spectrogram
    Column(modifier = modifier) {
        when {
            spectrogram != null -> SpectrumChart(
                spectrogram = spectrogram,
                durationMs = uiState.durationMs,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            uiState.analyzing -> SpectrumProgress(
                progress = uiState.progress,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            else -> SpectrumUnavailable(modifier = Modifier.fillMaxWidth().weight(1f))
        }
        SpectrumTrackInfo(
            format = uiState.signalFormat,
            sizeBytes = uiState.sizeBytes,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        if (uiState.analysis.hasVerdict()) {
            SpectrumAnalysisPanel(
                analysis = uiState.analysis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
