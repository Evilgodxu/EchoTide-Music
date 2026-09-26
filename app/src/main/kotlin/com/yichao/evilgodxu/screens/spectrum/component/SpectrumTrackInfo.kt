package com.yichao.evilgodxu.screens.spectrum.component

import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.data.music.playback.AudioSignalPathFormat
import com.yichao.evilgodxu.utils.formatBytes

// 参数项之间的分隔符
private const val ITEM_SEPARATOR = " · "

// 音频参数行：格式、规格与体积串成一行并居中显示，宽度不足时自然换行。
// 置于图下方而非侧边，避免挤占频谱图的横向空间
@Composable
internal fun SpectrumTrackInfo(
    format: AudioSignalPathFormat?,
    sizeBytes: Long,
    modifier: Modifier = Modifier,
) {
    val text = spectrumInfoText(LocalContext.current, format, sizeBytes)
    if (text.isBlank()) return
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 11.sp,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth(),
    )
}

// 参数行文案：界面与导出图共用，避免两处口径漂移；仅拼接实际读到的项，
// 无可展示项时返回空串由调用方跳过该行
internal fun spectrumInfoText(
    context: Context,
    format: AudioSignalPathFormat?,
    sizeBytes: Long,
): String {
    val channelText = format?.channels?.let {
        when (it) {
            1 -> context.getString(R.string.spectrum_info_mono)
            2 -> context.getString(R.string.spectrum_info_stereo)
            else -> context.getString(R.string.spectrum_info_channel_count, it)
        }
    }
    return buildString {
        if (format != null) {
            format.format?.takeIf { it.isNotBlank() }?.let { append(it).append(ITEM_SEPARATOR) }
            format.sampleRate?.let { append(formatSampleRate(it)).append(ITEM_SEPARATOR) }
            format.bitDepth?.let { append("${it}bit").append(ITEM_SEPARATOR) }
            channelText?.let { append(it).append(ITEM_SEPARATOR) }
            format.bitrate?.let { append("${it}kbps").append(ITEM_SEPARATOR) }
        }
        if (sizeBytes > 0L) append(formatBytes(sizeBytes)).append(ITEM_SEPARATOR)
    }.removeSuffix(ITEM_SEPARATOR)
}

// 采样率按千赫兹展示，整千时省去小数位
private fun formatSampleRate(hertz: Int): String =
    if (hertz % 1000 == 0) "${hertz / 1000}kHz" else "%.1fkHz".format(hertz / 1000f)
