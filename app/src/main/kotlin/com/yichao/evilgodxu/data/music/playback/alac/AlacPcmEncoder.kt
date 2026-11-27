package com.yichao.evilgodxu.data.music.playback.alac

import java.nio.ByteBuffer

/**
 * 写出编码：与平台 AudioFormat 解耦
 * 只表达字节排布，映射由渲染器统一完成。
 */
internal enum class AlacOutputEncoding(
    val bytesPerSample: Int,
) {
    /** 32 位浮点：直出与混音都接受，不丢精度，首选 */
    FLOAT(4),
    INT16(2),
    INT24(3),
    INT32(4),
}

/**
 * 逐声道整数样本按输出编码交错写出。
 * 位深与编码可不同：宽转窄截断、窄转宽补齐
 */
internal object AlacPcmEncoder {

    /** 交错写出后一帧的字节数 */
    fun frameByteCount(
        sampleCount: Int,
        channelCount: Int,
        encoding: AlacOutputEncoding,
    ): Int = sampleCount * channelCount * encoding.bytesPerSample

    /** 按声道交错写入 out；样本按 bitDepth 位表示 */
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

    /** 两种字长间平移：源更宽截断，更窄补齐 */
    private fun rescale(value: Int, fromBits: Int, toBits: Int): Int =
        if (fromBits > toBits) value shr (fromBits - toBits) else value shl (toBits - fromBits)

    private fun putInt24(out: ByteBuffer, value: Int) {
        out.put((value and 0xFF).toByte())
        out.put(((value ushr 8) and 0xFF).toByte())
        out.put(((value ushr 16) and 0xFF).toByte())
    }
}
