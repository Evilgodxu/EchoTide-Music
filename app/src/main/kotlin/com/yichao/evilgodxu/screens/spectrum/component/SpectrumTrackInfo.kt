package com.yichao.evilgodxu.screens.spectrum.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
    val channelText = when {
        format == null -> ""
        format.channels == 1 -> stringResource(R.string.spectrum_info_mono)
        format.channels == 2 -> stringResource(R.string.spectrum_info_stereo)
        else -> stringResource(R.string.spectrum_info_channel_count, format.channels)
    }
    val text = buildString {
        if (format != null) {
            if (format.format.isNotBlank()) append(format.format).append(ITEM_SEPARATOR)
            append(formatSampleRate(format.sampleRate)).append(ITEM_SEPARATOR)
            append("${format.bitDepth}bit").append(ITEM_SEPARATOR)
            append(channelText).append(ITEM_SEPARATOR)
            if (format.bitrate > 0) append("${format.bitrate}kbps").append(ITEM_SEPARATOR)
        }
        if (sizeBytes > 0L) append(formatBytes(sizeBytes)).append(ITEM_SEPARATOR)
    }.removeSuffix(ITEM_SEPARATOR)
    if (text.isBlank()) return
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 11.sp,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth(),
    )
}

// 采样率按千赫兹展示，整千时省去小数位
private fun formatSampleRate(hertz: Int): String =
    if (hertz % 1000 == 0) "${hertz / 1000}kHz" else "%.1fkHz".format(hertz / 1000f)
