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

// 编解码缓冲各 8 帧，解码与输出互不等待
private const val NUM_INPUT_BUFFERS = 8
private const val NUM_OUTPUT_BUFFERS = 8

/**
 * 系统无 ALAC 解码器，不接管则整轨不可解码
 * 厂商解码器仍优先，本渲染器只在系统解不了时补上。
 * 浮点与 MediaCodec 同口径，不受理退回整型
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
        // 无 cookie 解不出帧，不接管，也不猜参数
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
     * 浮点优先且须接收器直接受理，同 MediaCodec
     * 不受理则按源码位深取整型；20 位按 24 位写。
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

    /** ALAC 参数随容器下发，取首段初始化数据 */
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
