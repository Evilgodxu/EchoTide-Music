package com.yichao.evilgodxu.screens.spectrum.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.yichao.evilgodxu.screens.spectrum.SpectrumUiState

// 频谱内容：按分析状态分发——分析中报进度、有结果则铺满时频图、否则给出不可分析占位。
// 时频图方向由组装器按窗口朝向给出
@Composable
internal fun SpectrumBody(
    uiState: SpectrumUiState,
    vertical: Boolean,
    modifier: Modifier = Modifier,
) {
    val spectrogram = uiState.spectrogram
    when {
        spectrogram != null -> SpectrumChart(
            spectrogram = spectrogram,
            durationMs = uiState.durationMs,
            vertical = vertical,
            modifier = modifier,
        )
        uiState.analyzing -> SpectrumProgress(progress = uiState.progress, modifier = modifier)
        else -> SpectrumUnavailable(modifier = modifier)
    }
}
