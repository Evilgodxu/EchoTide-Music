package com.yichao.evilgodxu.data.music.playback

import android.media.AudioFormat

// 输出设备类别：USB、蓝牙、扬声器、有线与其它
enum class OutputDeviceKind { USB, BLUETOOTH, SPEAKER, WIRED, OTHER }

// 蓝牙链路类型：经典、低功耗或双模
enum class BluetoothLinkType { CLASSIC, LE, DUAL }

// 蓝牙编解码器声道模式
enum class BluetoothChannelMode { MONO, STEREO }

// 蓝牙编解码器参数，未上报项为空
data class BluetoothCodecInfo(
    /** 编解码器名称 */
    val name: String?,
    /** 编码采样率 */
    val sampleRateHz: Int?,
    /** 每样本位数与源位深无关；24 位需 aptX HD/LDAC */
    val bitsPerSample: Int?,
    val channelMode: BluetoothChannelMode?,
)

// 蓝牙链路附加信息，读不到为空
data class BluetoothLinkInfo(
    val linkType: BluetoothLinkType?,
    val deviceClass: Int?,
    /** 当前编解码器参数，未协商完成时 null */
    val codec: BluetoothCodecInfo?,
)

// 支持采样率的来源；设备声明与平台上报可能不一致
enum class SampleRateSource { DEVICE, PLATFORM }

// 当前输出设备信息；未授权时蓝牙名称与地址读不到
data class OutputDeviceInfo(
    val kind: OutputDeviceKind,
    val name: String?,
    val address: String?,
    /** 设备支持采样率，自报与声明的并集，升序 */
    val supportedSampleRates: List<Int>,
    /** supportedSampleRates 的出处 */
    val sampleRateSource: SampleRateSource?,
    val channelCount: Int?,
    /** 动态混音端口声明的编码，未开放端口为空 */
    val supportedEncodings: List<OutputEncoding>,
    /** 蓝牙链路附加信息；非蓝牙为 null */
    val bluetooth: BluetoothLinkInfo?,
)

/** 音频输出模式：源格式直出 / USB 独占 / 系统混音 */
enum class AudioOutputMode {
    FORMAT_LOCKED,
    EXCLUSIVE,
    MIXER,
}

/** 独占接管的设备；已从系统音频消失，只能由接管方给出 */
data class UsbExclusiveDeviceInfo(
    val name: String,
    /** 设备声明的采样率，UAC2 档位由时钟查询补入 */
    val supportedSampleRates: List<Int>,
    /** 有效位深对应的写出编码，升序去重 */
    val supportedEncodings: List<OutputEncoding>,
    /** 设备声明的通道数 */
    val channelCount: Int?,
    /** UAC 版本，0 为未找到 AudioControl */
    val uacVersion: Int? = null,
)

/** 有效位深转展示编码，归不了一档返回 null */
internal fun outputEncodingOfBitDepth(bitDepth: Int): OutputEncoding? = when (bitDepth) {
    8 -> OutputEncoding.PCM_8BIT
    16 -> OutputEncoding.PCM_16BIT
    24 -> OutputEncoding.PCM_24BIT
    32 -> OutputEncoding.PCM_32BIT
    else -> null
}

// 音频传输状态：由播放器的播放状态与起播意愿共同判定
enum class AudioTransportState { PLAYING, BUFFERING, PAUSED, ENDED, IDLE }

/** 浮点写出状态；未建立与未启用须分开 */
enum class FloatOutputState {
    /** 浮点变体生效：以 32 位浮点写出 */
    ENABLED,

    /** 降级为整型变体 */
    DISABLED,

    /** 输出尚未建立 */
    NOT_ESTABLISHED,
}

/** 输出编码，取音频轨实际写出值，不按源格式推算 */
enum class OutputEncoding {
    PCM_8BIT,
    PCM_16BIT,
    PCM_24BIT,
    PCM_32BIT,
    PCM_FLOAT,

    /** 输出尚未建立，无从取得写出编码 */
    NOT_ESTABLISHED,
}

/** 平台编码转展示编码，非线性 PCM 返回 null */
internal fun outputEncodingOf(pcmEncoding: Int): OutputEncoding? = when (pcmEncoding) {
    AudioFormat.ENCODING_PCM_8BIT -> OutputEncoding.PCM_8BIT
    AudioFormat.ENCODING_PCM_16BIT -> OutputEncoding.PCM_16BIT
    AudioFormat.ENCODING_PCM_24BIT_PACKED -> OutputEncoding.PCM_24BIT
    AudioFormat.ENCODING_PCM_32BIT -> OutputEncoding.PCM_32BIT
    AudioFormat.ENCODING_PCM_FLOAT -> OutputEncoding.PCM_FLOAT
    else -> null
}

/** 解码头实际输出格式；与源格式不同，是直出能否挂流的依据 */
data class DecodedOutputFormat(
    val sampleRate: Int,
    val channelCount: Int,
    val pcmEncoding: Int,
)

/**
 * 当前播放音频信息快照，null 表示该项不可获取。
 * 输出延迟不在此列：它按固定间隔单独采样。
 */
data class AudioInfoSnapshot(
    // 音频源
    val sourcePath: String?,
    /** 源文件字节数，在线或读不到为 null */
    val fileSizeBytes: Long?,
    val format: String?,
    val decoder: String?,
    /** 本机无解码器时的格式名，非空即无法解码 */
    val decoderUnavailableFormat: String?,
    // 音频参数
    val sourceSampleRate: Int?,
    /** 设备链路实际速率；倍率档下不同于源速率，混音取设备侧 */
    val outputSampleRate: Int?,
    val bitrateKbps: Int?,
    val channelCount: Int?,
    val bitDepth: Int?,
    val lossless: Boolean?,
    // 播放链路
    val outputMode: AudioOutputMode?,
    /** 解码头输出格式，未上报时 null */
    val decodedOutput: DecodedOutputFormat?,
    val audioSessionId: Int?,
    val floatOutput: FloatOutputState?,
    val outputEncoding: OutputEncoding?,
    val transportState: AudioTransportState?,
    // 输出设备
    val outputDevice: OutputDeviceInfo?,
) {
    /** 输出侧变速方式：源速率与写出速率不一致时发生了什么 */
    val resampleState: ResampleState?
        get() {
            val source = sourceSampleRate ?: return null
            val output = outputSampleRate ?: return null
            return when (outputMode) {
                AudioOutputMode.MIXER ->
                    if (source != output) ResampleState.SYSTEM else ResampleState.NONE

                // 直出按源格式，唯一改速率的是同族倍率档
                AudioOutputMode.FORMAT_LOCKED ->
                    if (source != output) ResampleState.SAME_FAMILY else ResampleState.NONE

                // 独占按源速率写出，显式分支为可读性
                AudioOutputMode.EXCLUSIVE -> ResampleState.NONE
                null -> null
            }
        }
}

/** 输出速率与源速率的关系：未变 / 系统重采样 / 同族插值 */
enum class ResampleState { NONE, SYSTEM, SAME_FAMILY }
