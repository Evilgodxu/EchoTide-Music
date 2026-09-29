package com.yichao.evilgodxu.data.music.playback

// 输出设备类别：区分外接 USB 解码器、蓝牙音频与内置扬声器
enum class OutputDeviceKind { USB, BLUETOOTH, SPEAKER }

// 单个输出设备的信息。各项均为平台上报值，未上报的项留空；
// 名称受权限限制可能不可得（蓝牙设备名需 BLUETOOTH_CONNECT），由展示层决定是否退回地址作为标识
data class OutputDeviceInfo(
    val kind: OutputDeviceKind,
    val name: String?,
    val address: String?,
    val supportedSampleRates: List<Int>,
    val channelCount: Int?,
)

// 音频输出模式：位完美独占（不经混音器、不重采样直出）与系统混音
enum class AudioOutputMode { BIT_PERFECT, MIXER }

// 音频传输状态：由播放器的播放状态与起播意愿共同判定
enum class AudioTransportState { PLAYING, BUFFERING, PAUSED, ENDED, IDLE }

/**
 * 当前播放音频的信息快照。
 *
 * 每个可选项为 null 均表示该项在本次播放链路中不可获取（平台或设备未上报、尚未起播、权限不足），
 * 展示层据此跳过对应行；不写占位值顶替，避免把未知项展示成真实信息。
 */
data class AudioInfoSnapshot(
    // 音频源
    val sourcePath: String?,
    val format: String?,
    val decoder: String?,
    // 音频参数
    val sourceSampleRate: Int?,
    val outputSampleRate: Int?,
    val bitrateKbps: Int?,
    val channelCount: Int?,
    val bitDepth: Int?,
    val lossless: Boolean?,
    // 播放链路
    val outputMode: AudioOutputMode?,
    val audioSessionId: Int?,
    val floatOutput: Boolean?,
    val latencyMs: Float?,
    val transportState: AudioTransportState?,
    // 输出设备
    val outputDevices: List<OutputDeviceInfo>,
) {
    // 是否发生重采样：原始采样率与输出采样率都已知时才可判定，任一未知即无从比较
    val resampled: Boolean?
        get() = if (sourceSampleRate != null && outputSampleRate != null) {
            sourceSampleRate != outputSampleRate
        } else {
            null
        }
}
