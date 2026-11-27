package com.yichao.evilgodxu.data.music.playback.alac

import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderException
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.SimpleDecoder
import androidx.media3.decoder.SimpleDecoderOutputBuffer

/**
 * media3 适配层：帧解码 + PCM 编码
 * 输出编码定妥后不改；解码不跨帧保存状态。
 */
@UnstableApi
internal class AlacDecoder(
    config: AlacSpecificConfig,
    val outputEncoding: AlacOutputEncoding,
    numInputBuffers: Int,
    numOutputBuffers: Int,
    maxInputBufferSize: Int,
) : SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, AlacDecoderException>(
    arrayOfNulls(numInputBuffers),
    arrayOfNulls(numOutputBuffers),
) {

    val channelCount: Int = config.channelCount

    val sampleRate: Int = config.sampleRate

    private val frameDecoder = AlacFrameDecoder(config)

    private val bitDepth: Int = config.bitDepth

    /** 输入缓冲不保证可访问底层数组，先拷入复用缓冲 */
    private var inputScratch = ByteArray(0)

    init {
        setInitialInputBufferSize(maxInputBufferSize)
    }

    override fun getName(): String = NAME

    override fun createInputBuffer(): DecoderInputBuffer =
        DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)

    override fun createOutputBuffer(): SimpleDecoderOutputBuffer =
        SimpleDecoderOutputBuffer(this::releaseOutputBuffer)

    override fun createUnexpectedDecodeException(error: Throwable): AlacDecoderException =
        AlacDecoderException("ALAC 解码出现意外错误", error)

    override fun decode(
        inputBuffer: DecoderInputBuffer,
        outputBuffer: SimpleDecoderOutputBuffer,
        reset: Boolean,
    ): AlacDecoderException? {
        val data = inputBuffer.data ?: return AlacDecoderException("输入缓冲为空")
        val size = data.remaining()
        if (size <= 0) return AlacDecoderException("输入缓冲为长度 0 的帧")

        val bytes = scratchFor(size)
        data.duplicate().get(bytes, 0, size)

        try {
            frameDecoder.decode(bytes, size)
        } catch (e: RuntimeException) {
            return AlacDecoderException("ALAC 帧解码失败", e)
        }

        val samples = frameDecoder.sampleCount
        val byteCount = AlacPcmEncoder.frameByteCount(samples, channelCount, outputEncoding)
        val target = outputBuffer.init(inputBuffer.timeUs, byteCount)
        AlacPcmEncoder.encode(
            frameDecoder.channels,
            samples,
            channelCount,
            bitDepth,
            outputEncoding,
            target,
        )
        // 交出时位置归 0、上界为已写字节数
        target.position(0)
        target.limit(byteCount)
        return null
    }

    private fun scratchFor(size: Int): ByteArray {
        if (inputScratch.size < size) inputScratch = ByteArray(size)
        return inputScratch
    }

    /** 无线程与跨帧状态，释放即交回基类 */
    override fun release() {
        super.release()
        inputScratch = ByteArray(0)
    }

    private companion object {
        const val NAME = "echotide.alac.decoder"
    }
}

/** ALAC 解码失败：媒体3 会转成播放错误，不再重试 */
@UnstableApi
internal class AlacDecoderException(message: String, cause: Throwable? = null) :
    DecoderException(message, cause)
