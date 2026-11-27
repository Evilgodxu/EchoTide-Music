package com.yichao.evilgodxu.data.music.playback

/**
 * 混音链路设备侧速率：音频轨恒等源速率，重采样在混音器
 * 蓝牙取协商速率；其余取 HAL 速率，须在声明档位内
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
