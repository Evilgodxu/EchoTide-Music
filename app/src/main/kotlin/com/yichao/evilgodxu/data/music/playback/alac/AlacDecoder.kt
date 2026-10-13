package com.yichao.evilgodxu.data.music.playback.alac

import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderException
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.SimpleDecoder
import androidx.media3.decoder.SimpleDecoderOutputBuffer

/**
 * 自研 ALAC 解码器接入媒体3 的适配层。
 *
 * 媒体3 把「解压一帧」这件事抽象成逐帧进、逐帧出的解码器（[SimpleDecoder] 自带解码线程与缓冲池），
 * 本类只负责把一侧的字节缓冲接到另一侧：把输入缓冲交给 [AlacFrameDecoder]，
 * 再把解出的样本按选定的输出编码交给 [AlacPcmEncoder]。
 *
 * [outputEncoding] 由渲染器在挑选解码器时定妥，此后不再变化：写出格式是音频输出的配置输入，
 * 中途更改会让既有音频轨的格式与配置脱节。
 *
 * 解码本身不跨帧保存状态（每帧自带全部参数），故 [decode] 的 reset 无从处置也不必处置。
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

    /** 位流读取需要连续字节，而输入缓冲不保证是可访问底层数组的堆缓冲，故每次拷入复用缓冲 */
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
        // 输出缓冲以「位置 0、上界为已写字节数」交出，与媒体3 其它解码器的约定一致
        target.position(0)
        target.limit(byteCount)
        return null
    }

    private fun scratchFor(size: Int): ByteArray {
        if (inputScratch.size < size) inputScratch = ByteArray(size)
        return inputScratch
    }

    /** 解码器的线程归属由 [SimpleDecoder] 管理，本实现不留跨帧状态，故释放即交回基类 */
    override fun release() {
        super.release()
        inputScratch = ByteArray(0)
    }

    private companion object {
        const val NAME = "yichao.alac.decoder"
    }
}

/** ALAC 解码失败的载体：媒体3 会把解码器异常转成播放错误，不再重试 */
@UnstableApi
internal class AlacDecoderException(message: String, cause: Throwable? = null) :
    DecoderException(message, cause)
