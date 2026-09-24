package com.yichao.evilgodxu.screens.spectrum.component

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.data.music.analysis.Spectrogram
import com.yichao.evilgodxu.data.music.clip.SpectrumImageSharing
import com.yichao.evilgodxu.screens.spectrum.SpectrumUiState
import com.yichao.evilgodxu.screens.spectrum.hasVerdict
import kotlinx.coroutines.launch

// 上区槽位高度占内容区可用高度的比例：图与两侧刻度不占满整屏，剩余高度留在底部信息之下
private const val PLOT_SLOT_HEIGHT_FRACTION = 0.9f

// 上区槽位与顶部标题区的固定间距
private val PLOT_SLOT_TOP_GAP = 4.dp

// 底部信息块与频谱图的间距
private val BOTTOM_INFO_TOP_GAP = 4.dp

// 导出图中两路结论之间的分隔符
private const val VERDICT_SEPARATOR = "  ·  "

// 频谱内容：上区按时频分析状态分发——分析中报进度、有结果则铺开时频图与两侧刻度，
// 否则给出不可分析占位；三种状态共用同一槽位尺寸，切换时下半区不跳动。
// 下区紧随槽位排布：源文件参数行距图 4dp、曲目判定结论再随其后；槽位未占满的高度留在最下方。
// 分析未完成时结论显示校验中，该区域不会先空后跳。
// 与顶部标题区的 4dp 间距由槽位统一给出，组装器不得再叠加顶部留白。
// 长按频谱图导出高清图：由界面渲染，交数据层写入相册或分享
@Composable
internal fun SpectrumBody(
    uiState: SpectrumUiState,
    modifier: Modifier = Modifier,
) {
    val spectrogram = uiState.spectrogram
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val slotModifier = Modifier
        .fillMaxWidth()
        .fillMaxHeight(PLOT_SLOT_HEIGHT_FRACTION)
        .padding(top = PLOT_SLOT_TOP_GAP)
    Column(modifier = modifier) {
        when {
            spectrogram != null -> SpectrumChart(
                spectrogram = spectrogram,
                durationMs = uiState.durationMs,
                onShareImage = {
                    scope.launch { exportSpectrumImage(context, uiState, spectrogram, share = true) }
                },
                onSaveImage = {
                    scope.launch { exportSpectrumImage(context, uiState, spectrogram, share = false) }
                },
                modifier = slotModifier,
            )
            uiState.analyzing -> SpectrumProgress(progress = uiState.progress, modifier = slotModifier)
            else -> SpectrumUnavailable(modifier = slotModifier)
        }
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

// 导出频谱图：渲染高清图后写入相册或经分享面板分享，只在失败与保存成功时提示
private suspend fun exportSpectrumImage(
    context: Context,
    uiState: SpectrumUiState,
    spectrogram: Spectrogram,
    share: Boolean,
) {
    val png = SpectrumShareImage.render(spectrogram, spectrumShareContent(context, uiState))
    val fileName = "${uiState.title} ${context.getString(R.string.spectrum_export_name_suffix)}"
    val done = if (share) {
        SpectrumImageSharing.share(
            context = context,
            png = png,
            fileName = fileName,
            chooserTitle = context.getString(R.string.spectrum_share_chooser_title),
        )
    } else {
        SpectrumImageSharing.saveToAlbum(context, png, fileName)
    }
    val messageRes = when {
        !done && share -> R.string.spectrum_image_share_failed
        !done -> R.string.spectrum_image_save_failed
        share -> return
        else -> R.string.spectrum_image_saved
    }
    Toast.makeText(context, context.getString(messageRes), Toast.LENGTH_SHORT).show()
}

// 导出内容：文案取自与界面同一套口径（参数行、两路结论、右下角免责说明）
private fun spectrumShareContent(context: Context, uiState: SpectrumUiState): SpectrumShareContent {
    val fake = fakeLosslessDisplay(context, uiState.analysis)
    val ai = aiMusicDisplay(context, uiState.analysis)
    return SpectrumShareContent(
        title = uiState.title.ifBlank { context.getString(R.string.spectrum_screen_title) },
        durationMs = uiState.durationMs,
        info = spectrumInfoText(context, uiState.signalFormat, uiState.sizeBytes),
        verdicts = if (uiState.analysis.hasVerdict()) {
            listOf(
                SpectrumShareVerdict("${fake.label} ${fake.value}", fake.flagged),
                SpectrumShareVerdict(VERDICT_SEPARATOR, false),
                SpectrumShareVerdict("${ai.label} ${ai.value}", ai.flagged),
            )
        } else {
            emptyList()
        },
        disclaimer = context.getString(R.string.spectrum_export_disclaimer),
    )
}
