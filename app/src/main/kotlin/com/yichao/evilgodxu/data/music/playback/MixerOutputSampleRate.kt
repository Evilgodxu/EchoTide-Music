package com.yichao.evilgodxu.data.music.playback

/**
 * 混音模式下设备链路实际跑着的速率。
 *
 * 本应用那条音频轨的速率不是答案：它由输出层按解码输出格式建轨（见 [PcmAudioSink] 取
 * `format.sampleRate`），而 `AudioTrack.getSampleRate()` 在混音轨上回读的正是建轨时的请求值
 * （`AudioTrack::getSampleRate` 只在 offload / direct 轨上才回问 HAL），于是它与「解码输出」那一行
 * 重复，且永远等于源速率——系统重采样因此被一律误报成未发生。真正的重采样发生在音频轨之后：
 * 混音器按「轨速率 → 设备速率」的比例插值（`AudioMixerBase::TrackBase::setResampler`，日志原文即
 * "Creating resampler from track %d Hz to device %d Hz"），故这里要报的是设备侧那个速率。
 *
 * 两条来源，按路由不同：
 * - 蓝牙：A2DP 是平台按协商出的编码速率单独开的一条输出流，其混音线程跑在编码速率上；
 *   主输出属性报的是扬声器那一路，故这里只认协商结果，读不到就留空（LE Audio 没有该协商结果）。
 * - 其余通路：都挂在主输出那条流上，设备侧的速率即该流的 HAL 速率（`getPrimaryOutputSamplingRate`）。
 *   但只有当设备声明了该档时它才落在这台设备上——设备声明不了这档，平台会另开一条按设备档位跑的
 *   输出流，那条流的速率没有公开接口可查，此时留空而不拿主输出的速率顶替。
 */
internal fun mixerOutputSampleRateHz(device: OutputDeviceInfo?, platformRate: Int?): Int? {
    if (device?.kind == OutputDeviceKind.BLUETOOTH) {
        return device.bluetooth?.codec?.sampleRateHz
    }
    val rate = platformRate?.takeIf { it > 0 } ?: return null
    val declared = device?.supportedSampleRates.orEmpty()
    if (declared.isNotEmpty() && rate !in declared) return null
    return rate
}
