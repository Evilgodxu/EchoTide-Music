package com.yichao.evilgodxu.screens.spectrum.component

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
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
import kotlinx.coroutines.launch

// 上区槽位与顶部标题区的固定间距
private val PLOT_SLOT_TOP_GAP = 4.dp

// 底部信息块与频谱图的间距
private val BOTTOM_INFO_TOP_GAP = 4.dp

// 上区按时频状态分发，共用槽位避免跳动
@Composable
internal fun SpectrumBody(
    uiState: SpectrumUiState,
    modifier: Modifier = Modifier,
) {
    val spectrogram = uiState.spectrogram
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 底部避让导航栏，底缘留白由组装器给出
    Column(modifier = modifier.navigationBarsPadding()) {
        val slotModifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .padding(top = PLOT_SLOT_TOP_GAP)
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
    }
}

// 渲染高清图后分享或存相册
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

// 导出文案与界面同口径
private fun spectrumShareContent(context: Context, uiState: SpectrumUiState): SpectrumShareContent =
    SpectrumShareContent(
        title = uiState.title.ifBlank { context.getString(R.string.spectrum_screen_title) },
        artist = uiState.artist,
        durationMs = uiState.durationMs,
        info = spectrumInfoText(context, uiState.signalFormat, uiState.sizeBytes),
    )
