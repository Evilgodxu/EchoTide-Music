package com.yichao.evilgodxu.data.music.playback.usb

/**
 * 为一段解码输出挑选设备的播放格式。
 *
 * 判据只有一条：**设备收到的样本与解码输出逐位一致**。任何需要重采样、丢低位或做声道映射的搭配都不选，
 * 宁可返回 null 让调用方退回平台路径——独占直出的意义就在于原样送出，做不到时不成立比勉强出声更诚实。
 *
 * 因此三条硬性条件：格式必须是 PCM、声道数必须相等、采样率必须是设备已声明的档位。
 *
 * 判据落在**有效位深**（[UsbAudioOutputFormat.bitResolution]）而不是子槽宽度上：子槽只是存储宽度，
 * 设备真正认下的是有效位深——子槽 4 字节而有效位深 24 位的模式，送进去的 32 位源仍会被丢掉低八位。
 * 子槽宽度只用于挑"够装的更窄者"这一级并列比较（例如同为 24 位的 3 字节槽与 4 字节槽，取 3 字节）。
 *
 * 另留一档**软件音量的余量**：独占链路绕开了混音器，响度由本应用自己施加，而衰减是拿位深换来的——
 * 16 位子槽上一个 LSB 就是 -90 dBFS，默认 -30 dB 的衰减把 -60 dBFS 的细节正好压到 1 个 LSB 上，量化
 * 误差随即盖住它。故源位深不足 [HEADROOM_BIT_DEPTH] 时优先取容得下它的子槽（设备普遍声明 16/24 两档）：
 * 满幅时写出仍逐位原样——16 位样本落在 24 位槽的高两位、低八位为零，设备读到的还是那个值；一旦衰减，
 * 多出的 8 位就是 48 dB 的余量，四舍五入的误差落在 -138 dBFS 一档，低电平内容因此不会被量化掉。
 * 设备只声明更窄的档位时退回"够装即可"的原判据，退而不成才返回 null。
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
    // 先按留余量的位深挑；设备没有更宽的档位时才退回"够装的最窄一档"
    return usable.narrowestAtLeast(maxOf(sourceBitDepth, HEADROOM_BIT_DEPTH))
        ?: usable.narrowestAtLeast(sourceBitDepth)
}

/** 在 [usable] 里取有效位深不低于 [minBits] 的最窄一档；并列时取更窄的子槽 */
private fun List<UsbAudioOutputFormat>.narrowestAtLeast(minBits: Int): UsbAudioOutputFormat? =
    filter { it.bitResolution >= minBits }
        .minWithOrNull(compareBy<UsbAudioOutputFormat> { it.bitResolution }.thenBy { it.subslotBytes })

/**
 * 软件音量至少要留出的位深。
 *
 * 取 24 而不是"源位深 + 8"：24 位有符号的 LSB 已在 -138 dBFS，比任何录音里的细节都低，再宽只是白占
 * 带宽；而 16 位源的 LSB 只到 -90 dBFS，正好落在应用自持音量的衰减区间里，故只对窄源补这一档。
 */
private const val HEADROOM_BIT_DEPTH = 24

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
