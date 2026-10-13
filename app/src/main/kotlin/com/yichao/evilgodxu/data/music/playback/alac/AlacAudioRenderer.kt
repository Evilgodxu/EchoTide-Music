package com.yichao.evilgodxu.data.music.playback.alac

import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.decoder.CryptoConfig
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DecoderAudioRenderer

// 供解码器内部使用的缓冲池规模：进、出两侧各留若干帧，使解码线程与音频输出线程不必互相等待
private const val NUM_INPUT_BUFFERS = 8
private const val NUM_OUTPUT_BUFFERS = 8

/**
 * ALAC 音频渲染器：把自研解码器接到媒体3 的渲染器框架上。
 *
 * 存在的理由是缺口而非替代：AOSP 不含 `audio/alac` 的解码组件，媒体3 在解码器列表为空时会把整条
 * 音频轨判为不可解码（`FORMAT_UNSUPPORTED_SUBTYPE`），播放器随之不选中该轨——表现为静音走完、
 * 不报错，也没有解码器初始化回调。厂商自带的 ALAC 解码器仍优先使用（本渲染器排在系统渲染器之后，
 * 音轨选择器取先命中者），本渲染器只在系统解不了时补上。
 *
 * 输出格式按接收器的能力基准取浮点：本应用的音频输出以浮点写出，媒体3 的 MediaCodec 路径同样只在
 * 接收器能直接受理浮点时向解码器索取浮点输出，两者取同一口径，直出（按解码输出格式挑选混音器属性）
 * 与音频信息面板因此不会因格式来源不同而出现两套结论。接收器不受理浮点时退回源码位深对应的整型。
 */
@OptIn(UnstableApi::class)
internal class AlacAudioRenderer(
    eventHandler: Handler?,
    eventListener: AudioRendererEventListener?,
    audioSink: AudioSink,
) : DecoderAudioRenderer<AlacDecoder>(eventHandler, eventListener, audioSink) {

    override fun getName(): String = NAME

    override fun supportsFormatInternal(format: Format): Int {
        if (!MimeTypes.AUDIO_ALAC.equals(format.sampleMimeType, ignoreCase = true)) {
            return C.FORMAT_UNSUPPORTED_TYPE
        }
        // 没有 cookie 就解不出帧：这种轨道（如由清单下发、不含容器扩展）不接管，
        // 保持本渲染器出现之前的行为，不以猜出来的参数去解
        val config = configOf(format) ?: return C.FORMAT_UNSUPPORTED_TYPE
        if (format.cryptoType != C.CRYPTO_TYPE_NONE) {
            return C.FORMAT_UNSUPPORTED_DRM
        }
        val probe = Util.getPcmFormat(
            outputEncodingFor(config).pcmEncoding(),
            config.channelCount,
            config.sampleRate,
        )
        return if (sinkSupportsFormat(probe)) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_SUBTYPE
    }

    override fun createDecoder(format: Format, cryptoConfig: CryptoConfig?): AlacDecoder {
        val config = configOf(format) ?: throw AlacDecoderException("ALAC magic cookie 缺失或无法解析")
        return AlacDecoder(
            config = config,
            outputEncoding = outputEncodingFor(config),
            numInputBuffers = NUM_INPUT_BUFFERS,
            numOutputBuffers = NUM_OUTPUT_BUFFERS,
            maxInputBufferSize = format.maxInputSize.takeIf { it != Format.NO_VALUE }
                ?: config.maxFrameBytes,
        )
    }

    override fun getOutputFormat(decoder: AlacDecoder): Format = Util.getPcmFormat(
        decoder.outputEncoding.pcmEncoding(),
        decoder.channelCount,
        decoder.sampleRate,
    )

    /**
     * 选出写出编码。
     *
     * 浮点优先且要求接收器**直接受理**：与媒体3 的解码头取同一判据（它同样只在直接受理时索取浮点输出），
     * 否则同一首歌在两条路径下会拿到不同的解码输出格式。接收器不受理浮点时退回源码位深对应的整型：
     * 位深是源文件属性，按它写出才不丢精度；20 位没有对应的整型编码，按 24 位写出（左移补齐，不丢位）。
     */
    private fun outputEncodingFor(config: AlacSpecificConfig): AlacOutputEncoding {
        val floatFormat = Util.getPcmFormat(
            C.ENCODING_PCM_FLOAT,
            config.channelCount,
            config.sampleRate,
        )
        if (getSinkFormatSupport(floatFormat) == AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY) {
            return AlacOutputEncoding.FLOAT
        }
        return when (config.bitDepth) {
            32 -> AlacOutputEncoding.INT32
            24, 20 -> AlacOutputEncoding.INT24
            else -> AlacOutputEncoding.INT16
        }
    }

    /** 容器给出的编解码参数：ALAC 的码流参数一律随容器下发，取首段初始化数据 */
    private fun configOf(format: Format): AlacSpecificConfig? =
        format.initializationData.firstOrNull()?.let(AlacSpecificConfig::parse)

    private companion object {
        const val NAME = "AlacAudioRenderer"
    }
}

private fun AlacOutputEncoding.pcmEncoding(): Int = when (this) {
    AlacOutputEncoding.FLOAT -> C.ENCODING_PCM_FLOAT
    AlacOutputEncoding.INT16 -> C.ENCODING_PCM_16BIT
    AlacOutputEncoding.INT24 -> C.ENCODING_PCM_24BIT
    AlacOutputEncoding.INT32 -> C.ENCODING_PCM_32BIT
}
