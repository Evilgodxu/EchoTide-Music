package com.yichao.evilgodxu.data.music.playback.alac

import java.nio.ByteBuffer

/**
 * 解码样本的落盘编码。
 *
 * 这里不直接用平台的 `AudioFormat` 编码常量：那些常量在单元测试的 JVM 上是桩值，
 * 而本枚举表达的是「字节怎么排」这件事本身，与平台取值无关，映射由调用方（渲染器）在一处完成。
 */
internal enum class AlacOutputEncoding(
    val bytesPerSample: Int,
) {
    /** 32 位浮点：直出与系统混音都接受，且不丢解码精度，是本应用的首选 */
    FLOAT(4),
    INT16(2),
    INT24(3),
    INT32(4),
}

/**
 * 把逐声道的整数样本按输出编码交错写出。
 *
 * 解码结果按源码字宽表示（见 [AlacFrameDecoder]），位深与输出编码不必相同：写 24 位输出时 16 位源
 * 左移补齐、写 16 位输出时 24 位源右移截断——各档之间是同一批样本的不同字长表示，不是重采样。
 */
internal object AlacPcmEncoder {

    /** 交错写出后一帧的字节数 */
    fun frameByteCount(
        sampleCount: Int,
        channelCount: Int,
        encoding: AlacOutputEncoding,
    ): Int = sampleCount * channelCount * encoding.bytesPerSample

    /** 把 [sampleCount] 个样本按声道交错写入 [out]；样本按 [bitDepth] 位表示 */
    fun encode(
        channels: Array<IntArray>,
        sampleCount: Int,
        channelCount: Int,
        bitDepth: Int,
        encoding: AlacOutputEncoding,
        out: ByteBuffer,
    ) {
        when (encoding) {
            AlacOutputEncoding.FLOAT -> {
                val scale = (1L shl (bitDepth - 1)).toFloat()
                for (index in 0 until sampleCount) {
                    for (channel in 0 until channelCount) {
                        out.putFloat(channels[channel][index] / scale)
                    }
                }
            }
            AlacOutputEncoding.INT16 -> {
                for (index in 0 until sampleCount) {
                    for (channel in 0 until channelCount) {
                        out.putShort(
                            rescale(channels[channel][index], bitDepth, 16).toShort(),
                        )
                    }
                }
            }
            AlacOutputEncoding.INT24 -> {
                for (index in 0 until sampleCount) {
                    for (channel in 0 until channelCount) {
                        putInt24(out, rescale(channels[channel][index], bitDepth, 24))
                    }
                }
            }
            AlacOutputEncoding.INT32 -> {
                for (index in 0 until sampleCount) {
                    for (channel in 0 until channelCount) {
                        out.putInt(rescale(channels[channel][index], bitDepth, 32))
                    }
                }
            }
        }
    }

    /** 同一批样本在两种字长之间的平移；源更宽时截断，更窄时补齐 */
    private fun rescale(value: Int, fromBits: Int, toBits: Int): Int =
        if (fromBits > toBits) value shr (fromBits - toBits) else value shl (toBits - fromBits)

    private fun putInt24(out: ByteBuffer, value: Int) {
        out.put((value and 0xFF).toByte())
        out.put(((value ushr 8) and 0xFF).toByte())
        out.put(((value ushr 16) and 0xFF).toByte())
    }
}
