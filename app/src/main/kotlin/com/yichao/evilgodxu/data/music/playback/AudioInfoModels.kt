package com.yichao.evilgodxu.data.music.playback

// 输出设备类别：区分外接 USB 解码器、蓝牙音频、内置扬声器、有线耳机与其它通路
enum class OutputDeviceKind { USB, BLUETOOTH, SPEAKER, WIRED, OTHER }

// 蓝牙链路类型：经典蓝牙承载 A2DP/SCO，低功耗蓝牙承载 LE Audio，双模两者兼有
enum class BluetoothLinkType { CLASSIC, LE, DUAL }

// 蓝牙链路的附加信息：取自蓝牙栈而非音频栈，读不到时各项为空
data class BluetoothLinkInfo(
    val linkType: BluetoothLinkType?,
    val deviceClass: Int?,
)

// 当前输出设备的信息。各项均为平台上报值，未上报的项留空；
// 蓝牙设备的名称与真实地址受 BLUETOOTH_CONNECT 限制，未授权时留空，由展示层决定是否退回地址作为标识
data class OutputDeviceInfo(
    val kind: OutputDeviceKind,
    val name: String?,
    val address: String?,
    val supportedSampleRates: List<Int>,
    val channelCount: Int?,
    /** 蓝牙链路的附加信息；非蓝牙设备为 null */
    val bluetooth: BluetoothLinkInfo?,
)

// 音频输出模式：位完美独占（不经混音器、不重采样直出）与系统混音
enum class AudioOutputMode { BIT_PERFECT, MIXER }

// 音频传输状态：由播放器的播放状态与起播意愿共同判定
enum class AudioTransportState { PLAYING, BUFFERING, PAUSED, ENDED, IDLE }

/**
 * 浮点写出状态。
 *
 * 输出链路的取向与「输出是否已建立」是两件事：前者是变体决策的结果，后者说明链路尚未起播。
 * 二者合并成同一个布尔值时，未起播会与未启用显示成同一种取值。
 */
enum class FloatOutputState {
    /** 浮点变体生效：高分辨率源以 32 位浮点写出 */
    ENABLED,

    /** 降级为整型变体：高分辨率源被降回 16 位整型写出 */
    DISABLED,

    /** 输出尚未建立（未起播或已停止），此时链路取向无从谈起 */
    NOT_ESTABLISHED,
}

/**
 * 输出链路的 PCM 编码。
 *
 * 取音频轨被创建时的实际写出编码，不按源格式或变体推算：16 位及以下源在浮点变体下同样写成整型，
 * 位完美流又只接纳与混音器属性一致的格式，推算值未必等于真正写出的编码。
 */
enum class OutputEncoding {
    PCM_8BIT,
    PCM_16BIT,
    PCM_24BIT,
    PCM_32BIT,
    PCM_FLOAT,

    /** 输出尚未建立（未起播或已停止），此时无从取得写出编码 */
    NOT_ESTABLISHED,
}

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
    val floatOutput: FloatOutputState?,
    val outputEncoding: OutputEncoding?,
    val latencyMs: Float?,
    val transportState: AudioTransportState?,
    // 输出设备
    val outputDevice: OutputDeviceInfo?,
) {
    // 是否发生重采样：原始采样率与输出采样率都已知时才可判定，任一未知即无从比较
    val resampled: Boolean?
        get() = if (sourceSampleRate != null && outputSampleRate != null) {
            sourceSampleRate != outputSampleRate
        } else {
            null
        }
}
