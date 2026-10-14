package com.yichao.evilgodxu.data.music.playback.usb

/**
 * 为一段解码输出挑选设备的播放格式。
 *
 * 判据只有一条：**设备收到的样本与解码输出逐位一致**。任何需要重采样、丢低位或做声道映射的搭配都不选，
 * 宁可返回 null 让调用方退回平台路径——独占直出的意义就在于原样送出，做不到时不成立比勉强出声更诚实。
 *
 * 因此三条硬性条件：格式必须是 PCM、声道数必须相等、采样率必须是设备已声明的档位。
 *
 * 在满足条件的候选中取**最小够用**的位深，而不是一律挑设备最宽的那一档：本项目直出的口径是"止步于源
 * 格式"，用 32 位通道装 16 位源并不带来保真收益。
 *
 * 判据落在**有效位深**（[UsbAudioOutputFormat.bitResolution]）而不是子槽宽度上：子槽只是存储宽度，
 * 设备真正认下的是有效位深——子槽 4 字节而有效位深 24 位的模式，送进去的 32 位源仍会被丢掉低八位。
 * 子槽宽度只用于挑"够装的更窄者"这一级并列比较（例如同为 24 位的 3 字节槽与 4 字节槽，取 3 字节）。
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
            // 有效位深容不下源位深就只能丢低位，这类搭配一律不选
            format.bitResolution >= sourceBitDepth
    }
    return usable.minWithOrNull(
        compareBy<UsbAudioOutputFormat> { it.bitResolution }.thenBy { it.subslotBytes },
    )
}

/**
 * 采样率是否可用。
 *
 * 声明了离散档位或连续区间的设备按声明判；**未声明任何档位时判为可用**——那是 UAC2 的常态，它的档位
 * 不存在描述符里，只能由运行期问时钟得到答案，而运行期才是唯一有权威结论的地方（见
 * [UsbExclusiveDac.prepareStream]）。此处若把"未声明"当成"不支持"，会把所有 UAC2 设备挡在门外。
 */
private fun rateAvailable(format: UsbAudioOutputFormat, sampleRate: Int): Boolean {
    val declaresNothing = format.rates.isEmpty() && !format.continuousRates
    return declaresNothing || format.supportsRate(sampleRate)
}

/** 格式标签中表示 PCM（Type I）的取值 */
private const val PCM_FORMAT_TAG = 1
