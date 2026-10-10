package com.yichao.evilgodxu.data.music.playback

import android.media.AudioFormat

// 输出设备类别：区分外接 USB 解码器、蓝牙音频、内置扬声器、有线耳机与其它通路
enum class OutputDeviceKind { USB, BLUETOOTH, SPEAKER, WIRED, OTHER }

// 蓝牙链路类型：经典蓝牙承载 A2DP/SCO，低功耗蓝牙承载 LE Audio，双模两者兼有
enum class BluetoothLinkType { CLASSIC, LE, DUAL }

// 蓝牙编解码器声道模式
enum class BluetoothChannelMode { MONO, STEREO }

// 蓝牙编解码器参数：A2DP 链路上实际协商出的编码规格，各项未上报时为空
data class BluetoothCodecInfo(
    /** 编解码器名称，取自平台对编解码器标识的命名，厂商编解码器同样有名称 */
    val name: String?,
    /** 编码采样率 */
    val sampleRateHz: Int?,
    /** 每样本位数 */
    val bitsPerSample: Int?,
    val channelMode: BluetoothChannelMode?,
)

// 蓝牙链路的附加信息：取自蓝牙栈而非音频栈，读不到时各项为空
data class BluetoothLinkInfo(
    val linkType: BluetoothLinkType?,
    val deviceClass: Int?,
    /** 当前链路的编解码器参数；未协商完成或读不到时为 null */
    val codec: BluetoothCodecInfo?,
)

// 当前输出设备的信息。各项均为平台上报值，未上报的项留空；
// 蓝牙设备的名称与真实地址受 BLUETOOTH_CONNECT 限制，未授权时两项都读不到，同为留空
data class OutputDeviceInfo(
    val kind: OutputDeviceKind,
    val name: String?,
    val address: String?,
    /** 设备支持的全部采样率，升序：设备自报的档位与动态混音端口声明的档位取并集 */
    val supportedSampleRates: List<Int>,
    val channelCount: Int?,
    /**
     * 设备动态混音端口声明的 PCM 编码。
     *
     * 该端口是直出唯一能挂上的输出流，故它声明的编码即设备可直出的位深；未声明该端口的设备（非 USB 通路、
     * 厂商未开放端口）为空，由展示层跳过该行。
     */
    val supportedEncodings: List<OutputEncoding>,
    /** 蓝牙链路的附加信息；非蓝牙设备为 null */
    val bluetooth: BluetoothLinkInfo?,
)

/**
 * 音频输出模式。
 *
 * 直出分两档，差别在厂商是否在动态混音端口上声明了 AUDIO_OUTPUT_FLAG_BIT_PERFECT：
 * - 位完美直出：音频不经混音、不受音量与音效处理，数据原样下发到设备；
 * - 源格式直出：厂商漏标该标志时的兼容结果——输出流仍按源格式打开因而不发生重采样，
 *   但音轨音量与音效按常规链路处理。
 * 两者都谈不上时播放交系统混音器，输出采样率由系统决定，与源不一致即发生重采样。
 */
enum class AudioOutputMode { BIT_PERFECT, FORMAT_LOCKED, MIXER }

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
 * 平台 PCM 编码 → 展示用编码枚举。
 *
 * 只认线性 PCM：位完美流与直通输出给出的编码可能不属任何位深，归不了类即返回 null，
 * 由调用方决定是跳过该行还是跳过该片段。
 */
internal fun outputEncodingOf(pcmEncoding: Int): OutputEncoding? = when (pcmEncoding) {
    AudioFormat.ENCODING_PCM_8BIT -> OutputEncoding.PCM_8BIT
    AudioFormat.ENCODING_PCM_16BIT -> OutputEncoding.PCM_16BIT
    AudioFormat.ENCODING_PCM_24BIT_PACKED -> OutputEncoding.PCM_24BIT
    AudioFormat.ENCODING_PCM_32BIT -> OutputEncoding.PCM_32BIT
    AudioFormat.ENCODING_PCM_FLOAT -> OutputEncoding.PCM_FLOAT
    else -> null
}

/**
 * 解码头实际输出的格式。
 *
 * 与源格式是两回事：解码器会按需求改采样率与声道，编码更是要到解码头出格式才知道——容器格式对压缩源
 * 只给采样率与声道，pcmEncoding 仍是 NO_VALUE。这一项也是直出能否挂上专用输出流的直接依据：
 * 该流只接纳与解码输出逐字段一致的目标格式。
 *
 * [pcmEncoding] 为平台编码值，非 PCM 输出（直通）无位深可言。
 */
data class DecodedOutputFormat(
    val sampleRate: Int,
    val channelCount: Int,
    val pcmEncoding: Int,
)

/**
 * 当前播放音频的信息快照。
 *
 * 每个可选项为 null 均表示该项在本次播放链路中不可获取（平台或设备未上报、尚未起播、权限不足），
 * 展示层据此跳过对应行；不写占位值顶替，避免把未知项展示成真实信息。
 *
 * 输出延迟不在此列：它是随链路持续波动的观测量，按固定间隔单独采样（见 [OutputLatencySampler]），
 * 与本快照「随关键项变化才重算」的节奏不同，合并进来只会得到一个停留在旧时刻的数。
 */
data class AudioInfoSnapshot(
    // 音频源
    val sourcePath: String?,
    /** 源文件字节数；在线音源与读不到大小的本地源为 null */
    val fileSizeBytes: Long?,
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
    /** 解码头实际输出的格式；未装载曲目、格式尚未上报或位深不明时均为 null */
    val decodedOutput: DecodedOutputFormat?,
    val audioSessionId: Int?,
    val floatOutput: FloatOutputState?,
    val outputEncoding: OutputEncoding?,
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
