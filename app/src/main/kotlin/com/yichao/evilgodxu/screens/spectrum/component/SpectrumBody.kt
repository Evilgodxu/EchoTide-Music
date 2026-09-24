package com.yichao.evilgodxu.screens.spectrum.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yichao.evilgodxu.screens.spectrum.SpectrumUiState
import com.yichao.evilgodxu.screens.spectrum.hasVerdict

// 上区槽位高度占内容区可用高度的比例：图与两侧刻度不再拉伸占满整屏，多余高度留在中段
private const val PLOT_SLOT_HEIGHT_FRACTION = 0.5f

// 上区槽位与顶部标题区的固定间距
private val PLOT_SLOT_TOP_GAP = 4.dp

// 底部信息块与上方内容的间距
private val BOTTOM_INFO_TOP_GAP = 8.dp

// 频谱内容：上区按时频分析状态分发——分析中报进度、有结果则铺开时频图与两侧刻度，
// 否则给出不可分析占位；三种状态共用同一槽位尺寸，切换时下半区不跳动。
// 中段留白吸收槽位未用满的高度，使下区的源文件参数行与曲目判定结论仍贴在页面底部，
// 分析未完成时结论显示校验中，该区域不会先空后跳。
// 与顶部标题区的 4dp 间距由槽位统一给出，组装器不得再叠加顶部留白
@Composable
internal fun SpectrumBody(
    uiState: SpectrumUiState,
    modifier: Modifier = Modifier,
) {
    val spectrogram = uiState.spectrogram
    val slotModifier = Modifier
        .fillMaxWidth()
        .fillMaxHeight(PLOT_SLOT_HEIGHT_FRACTION)
        .padding(top = PLOT_SLOT_TOP_GAP)
    Column(modifier = modifier) {
        when {
            spectrogram != null -> SpectrumChart(
                spectrogram = spectrogram,
                durationMs = uiState.durationMs,
                modifier = slotModifier,
            )
            uiState.analyzing -> SpectrumProgress(progress = uiState.progress, modifier = slotModifier)
            else -> SpectrumUnavailable(modifier = slotModifier)
        }
        Spacer(Modifier.weight(1f))
        SpectrumTrackInfo(
            format = uiState.signalFormat,
            sizeBytes = uiState.sizeBytes,
            modifier = Modifier.fillMaxWidth().padding(top = BOTTOM_INFO_TOP_GAP),
        )
        if (uiState.analysis.hasVerdict()) {
            SpectrumAnalysisPanel(
                analysis = uiState.analysis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
