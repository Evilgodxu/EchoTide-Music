package com.yichao.evilgodxu.data.music.playback.usb

/**
 * 为解码输出挑选设备播放格式，判据是逐位一致。
 *
 * 须 PCM、声道相等、采样率在档；否则 null 退回平台。
 * 源位深不足 24 时取更宽子槽，给音量衰减留余量。
 */
internal fun planUsbOutput(
    sampleRate: Int,
    channelCount: Int,
    sourceBitDepth: Int,
    candidates: List<UsbAudioOutputFormat>,
): UsbAudioOutputFormat? {
    if (sampleRate <= 0 || channelCount <= 0 || sourceBitDepth <= 0) return null
    val usable = candidates.filter { format ->
        format.formatTag == PCM_FORMAT_TAG &&
            format.channels == channelCount &&
            rateAvailable(format, sampleRate) &&
            // 容不下源位深只能丢低位，一律不选
            format.bitResolution >= sourceBitDepth
    }
    // 先按余量挑，再退回最窄档
    return usable.narrowestAtLeast(maxOf(sourceBitDepth, HEADROOM_BIT_DEPTH))
        ?: usable.narrowestAtLeast(sourceBitDepth)
}

/** 取不低于 minBits 的最窄一档，并列取窄子槽 */
private fun List<UsbAudioOutputFormat>.narrowestAtLeast(minBits: Int): UsbAudioOutputFormat? =
    filter { it.bitResolution >= minBits }
        .minWithOrNull(compareBy<UsbAudioOutputFormat> { it.bitResolution }.thenBy { it.subslotBytes })

/**
 * 留出的位深 24。
 * 24 位 LSB 已很低，再宽白占带宽。
 */
private const val HEADROOM_BIT_DEPTH = 24

/**
 * 采样率是否可用，未声明档位时视为可用。
 * UAC2 档位要问时钟才知道，否则全被挡。
 */
private fun rateAvailable(format: UsbAudioOutputFormat, sampleRate: Int): Boolean {
    val declaresNothing = format.rates.isEmpty() && !format.continuousRates
    return declaresNothing || format.supportsRate(sampleRate)
}

/** 格式标签中表示 PCM（Type I）的取值 */
private const val PCM_FORMAT_TAG = 1
