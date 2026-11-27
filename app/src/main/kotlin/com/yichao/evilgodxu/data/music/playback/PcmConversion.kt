package com.yichao.evilgodxu.data.music.playback

import android.media.AudioFormat
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.roundToLong

/** 线性 PCM 读写：样本统一按左对齐 32 位整型中转 */
@OptIn(UnstableApi::class)
internal object PcmConversion {

    /** 播放头与帧计数都是按无符号 32 位回绕的值 */
    const val UNSIGNED_INT_MASK = 0xFFFFFFFFL

    private const val MICROS_PER_SECOND = 1_000_000L

    /** 每样本字节数；未知编码按调用方约定抛错，不做静默兜底 */
    fun byteDepth(pcmEncoding: Int): Int = when (pcmEncoding) {
        C.ENCODING_PCM_8BIT -> 1
        C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 2
        C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 3
        C.ENCODING_PCM_32BIT,
        C.ENCODING_PCM_32BIT_BIG_ENDIAN,
        C.ENCODING_PCM_FLOAT,
        C.ENCODING_PCM_FLOAT_BIG_ENDIAN -> 4

        C.ENCODING_PCM_DOUBLE, C.ENCODING_PCM_DOUBLE_BIG_ENDIAN -> 8
        else -> throw IllegalArgumentException("未知的 PCM 编码：$pcmEncoding")
    }

    /** 一帧（含全部声道）的字节数 */
    fun frameSize(pcmEncoding: Int, channelCount: Int): Int =
        byteDepth(pcmEncoding) * channelCount

    /** 是否为线性 PCM；压缩编码与直通编码都不在此列 */
    fun isLinearPcm(pcmEncoding: Int): Boolean = when (pcmEncoding) {
        C.ENCODING_PCM_8BIT,
        C.ENCODING_PCM_16BIT,
        C.ENCODING_PCM_16BIT_BIG_ENDIAN,
        C.ENCODING_PCM_24BIT,
        C.ENCODING_PCM_24BIT_BIG_ENDIAN,
        C.ENCODING_PCM_32BIT,
        C.ENCODING_PCM_32BIT_BIG_ENDIAN,
        C.ENCODING_PCM_FLOAT,
        C.ENCODING_PCM_FLOAT_BIG_ENDIAN,
        C.ENCODING_PCM_DOUBLE,
        C.ENCODING_PCM_DOUBLE_BIG_ENDIAN -> true

        else -> false
    }

    /** 是否高于 16 位的线性 PCM */
    fun isHighResolutionPcm(pcmEncoding: Int): Boolean = when (pcmEncoding) {
        C.ENCODING_PCM_24BIT,
        C.ENCODING_PCM_24BIT_BIG_ENDIAN,
        C.ENCODING_PCM_32BIT,
        C.ENCODING_PCM_32BIT_BIG_ENDIAN,
        C.ENCODING_PCM_FLOAT,
        C.ENCODING_PCM_FLOAT_BIG_ENDIAN,
        C.ENCODING_PCM_DOUBLE,
        C.ENCODING_PCM_DOUBLE_BIG_ENDIAN -> true

        else -> false
    }

    /** 声道掩码；不可映射时为 CHANNEL_INVALID */
    fun audioTrackChannelConfig(channelCount: Int): Int = when (channelCount) {
        1 -> AudioFormat.CHANNEL_OUT_MONO
        2 -> AudioFormat.CHANNEL_OUT_STEREO
        3 -> AudioFormat.CHANNEL_OUT_STEREO or AudioFormat.CHANNEL_OUT_FRONT_CENTER
        4 -> AudioFormat.CHANNEL_OUT_QUAD
        5 -> AudioFormat.CHANNEL_OUT_QUAD or AudioFormat.CHANNEL_OUT_FRONT_CENTER
        6 -> AudioFormat.CHANNEL_OUT_5POINT1
        7 -> AudioFormat.CHANNEL_OUT_5POINT1 or AudioFormat.CHANNEL_OUT_BACK_CENTER
        8 -> AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
        10 -> AudioFormat.CHANNEL_OUT_5POINT1POINT4
        12 -> AudioFormat.CHANNEL_OUT_7POINT1POINT4
        13 -> AudioFormat.CHANNEL_OUT_FRONT_LEFT or
            AudioFormat.CHANNEL_OUT_FRONT_CENTER or
            AudioFormat.CHANNEL_OUT_FRONT_RIGHT or
            AudioFormat.CHANNEL_OUT_SIDE_LEFT or
            AudioFormat.CHANNEL_OUT_SIDE_RIGHT or
            AudioFormat.CHANNEL_OUT_TOP_FRONT_LEFT or
            AudioFormat.CHANNEL_OUT_TOP_FRONT_CENTER or
            AudioFormat.CHANNEL_OUT_TOP_FRONT_RIGHT or
            AudioFormat.CHANNEL_OUT_TOP_BACK_LEFT or
            AudioFormat.CHANNEL_OUT_TOP_BACK_RIGHT or
            AudioFormat.CHANNEL_OUT_BOTTOM_FRONT_LEFT or
            AudioFormat.CHANNEL_OUT_BOTTOM_FRONT_CENTER or
            AudioFormat.CHANNEL_OUT_BOTTOM_FRONT_RIGHT

        14 -> AudioFormat.CHANNEL_OUT_9POINT1POINT4
        16 -> AudioFormat.CHANNEL_OUT_9POINT1POINT6
        24 -> AudioFormat.CHANNEL_OUT_7POINT1POINT4 or
            AudioFormat.CHANNEL_OUT_FRONT_LEFT_OF_CENTER or
            AudioFormat.CHANNEL_OUT_FRONT_RIGHT_OF_CENTER or
            AudioFormat.CHANNEL_OUT_BACK_CENTER or
            AudioFormat.CHANNEL_OUT_TOP_CENTER or
            AudioFormat.CHANNEL_OUT_TOP_FRONT_CENTER or
            AudioFormat.CHANNEL_OUT_TOP_BACK_CENTER or
            AudioFormat.CHANNEL_OUT_TOP_SIDE_LEFT or
            AudioFormat.CHANNEL_OUT_TOP_SIDE_RIGHT or
            AudioFormat.CHANNEL_OUT_BOTTOM_FRONT_LEFT or
            AudioFormat.CHANNEL_OUT_BOTTOM_FRONT_RIGHT or
            AudioFormat.CHANNEL_OUT_BOTTOM_FRONT_CENTER or
            AudioFormat.CHANNEL_OUT_LOW_FREQUENCY_2

        else -> AudioFormat.CHANNEL_INVALID
    }

    /** 读一个样本为左对齐 32 位；浮点先钳到 [-1,1] */
    fun readAs32BitIntPcm(buffer: ByteBuffer, pcmEncoding: Int): Int = when (pcmEncoding) {
        C.ENCODING_PCM_8BIT ->
            ((buffer.get().toInt() and 0xFF) - 128) shl 24

        C.ENCODING_PCM_16BIT ->
            ((buffer.get().toInt() and 0xFF) shl 16) or
                ((buffer.get().toInt() and 0xFF) shl 24)

        C.ENCODING_PCM_16BIT_BIG_ENDIAN ->
            ((buffer.get().toInt() and 0xFF) shl 24) or
                ((buffer.get().toInt() and 0xFF) shl 16)

        C.ENCODING_PCM_24BIT ->
            ((buffer.get().toInt() and 0xFF) shl 8) or
                ((buffer.get().toInt() and 0xFF) shl 16) or
                ((buffer.get().toInt() and 0xFF) shl 24)

        C.ENCODING_PCM_24BIT_BIG_ENDIAN ->
            ((buffer.get().toInt() and 0xFF) shl 24) or
                ((buffer.get().toInt() and 0xFF) shl 16) or
                ((buffer.get().toInt() and 0xFF) shl 8)

        C.ENCODING_PCM_32BIT ->
            (buffer.get().toInt() and 0xFF) or
                ((buffer.get().toInt() and 0xFF) shl 8) or
                ((buffer.get().toInt() and 0xFF) shl 16) or
                ((buffer.get().toInt() and 0xFF) shl 24)

        C.ENCODING_PCM_32BIT_BIG_ENDIAN ->
            ((buffer.get().toInt() and 0xFF) shl 24) or
                ((buffer.get().toInt() and 0xFF) shl 16) or
                ((buffer.get().toInt() and 0xFF) shl 8) or
                (buffer.get().toInt() and 0xFF)

        C.ENCODING_PCM_FLOAT -> floatToPcm32(constrain(buffer.float, -1f, 1f))

        C.ENCODING_PCM_FLOAT_BIG_ENDIAN ->
            floatToPcm32(
                constrain(
                    java.lang.Float.intBitsToFloat(Integer.reverseBytes(buffer.int)),
                    -1f,
                    1f,
                )
            )

        C.ENCODING_PCM_DOUBLE -> doubleToPcm32(constrain(buffer.double, -1.0, 1.0))

        C.ENCODING_PCM_DOUBLE_BIG_ENDIAN ->
            doubleToPcm32(
                constrain(
                    java.lang.Double.longBitsToDouble(java.lang.Long.reverseBytes(buffer.long)),
                    -1.0,
                    1.0,
                )
            )

        else -> throw IllegalStateException("未知的 PCM 编码：$pcmEncoding")
    }

    /** 把左对齐的 32 位整型写成目标编码，取高位字节 */
    fun write32BitIntPcm(buffer: ByteBuffer, pcm32Bit: Int, pcmEncoding: Int) {
        when (pcmEncoding) {
            C.ENCODING_PCM_32BIT -> {
                buffer.put(pcm32Bit.toByte())
                buffer.put((pcm32Bit shr 8).toByte())
                buffer.put((pcm32Bit shr 16).toByte())
                buffer.put((pcm32Bit shr 24).toByte())
            }

            C.ENCODING_PCM_24BIT -> {
                buffer.put((pcm32Bit shr 8).toByte())
                buffer.put((pcm32Bit shr 16).toByte())
                buffer.put((pcm32Bit shr 24).toByte())
            }

            C.ENCODING_PCM_16BIT -> {
                buffer.put((pcm32Bit shr 16).toByte())
                buffer.put((pcm32Bit shr 24).toByte())
            }

            C.ENCODING_PCM_8BIT -> buffer.put(((pcm32Bit shr 24) + 128).toByte())

            C.ENCODING_PCM_32BIT_BIG_ENDIAN -> {
                buffer.put((pcm32Bit shr 24).toByte())
                buffer.put((pcm32Bit shr 16).toByte())
                buffer.put((pcm32Bit shr 8).toByte())
                buffer.put(pcm32Bit.toByte())
            }

            C.ENCODING_PCM_24BIT_BIG_ENDIAN -> {
                buffer.put((pcm32Bit shr 24).toByte())
                buffer.put((pcm32Bit shr 16).toByte())
                buffer.put((pcm32Bit shr 8).toByte())
            }

            C.ENCODING_PCM_16BIT_BIG_ENDIAN -> {
                buffer.put((pcm32Bit shr 24).toByte())
                buffer.put((pcm32Bit shr 16).toByte())
            }

            C.ENCODING_PCM_FLOAT -> buffer.putFloat(pcm32ToFloat(pcm32Bit))

            C.ENCODING_PCM_FLOAT_BIG_ENDIAN ->
                buffer.putInt(
                    Integer.reverseBytes(java.lang.Float.floatToIntBits(pcm32ToFloat(pcm32Bit)))
                )

            C.ENCODING_PCM_DOUBLE -> buffer.putDouble(pcm32ToDouble(pcm32Bit))

            C.ENCODING_PCM_DOUBLE_BIG_ENDIAN ->
                buffer.putLong(
                    java.lang.Long.reverseBytes(
                        java.lang.Double.doubleToLongBits(pcm32ToDouble(pcm32Bit))
                    )
                )

            else -> throw IllegalStateException("未知的 PCM 编码：$pcmEncoding")
        }
    }

    fun sampleCountToDurationUs(sampleCount: Long, sampleRate: Int): Long {
        if (sampleRate <= 0) return 0L
        return sampleCount * MICROS_PER_SECOND / sampleRate
    }

    fun durationUsToSampleCount(durationUs: Long, sampleRate: Int): Long {
        if (sampleRate <= 0) return 0L
        return (durationUs * sampleRate + MICROS_PER_SECOND - 1) / MICROS_PER_SECOND
    }

    fun mediaDurationForPlayoutDuration(playoutDurationUs: Long, speed: Float): Long {
        if (speed == 1f) return playoutDurationUs
        return (playoutDurationUs.toDouble() * speed).roundToLong()
    }

    fun usToMs(durationUs: Long): Long = durationUs / 1_000L

    fun constrain(value: Float, min: Float, max: Float): Float = value.coerceIn(min, max)

    fun constrain(value: Double, min: Double, max: Double): Double = value.coerceIn(min, max)

    private fun floatToPcm32(value: Float): Int =
        if (value < 0f) {
            (-value * Int.MIN_VALUE).toInt()
        } else {
            (value * Int.MAX_VALUE).toInt()
        }

    private fun doubleToPcm32(value: Double): Int =
        if (value < 0.0) {
            (-value * Int.MIN_VALUE).toInt()
        } else {
            (value * Int.MAX_VALUE).toInt()
        }

    private fun pcm32ToFloat(pcm32Bit: Int): Float =
        if (pcm32Bit < 0) {
            -pcm32Bit.toFloat() / Int.MIN_VALUE
        } else {
            pcm32Bit.toFloat() / Int.MAX_VALUE
        }

    private fun pcm32ToDouble(pcm32Bit: Int): Double =
        if (pcm32Bit < 0) {
            -pcm32Bit.toDouble() / Int.MIN_VALUE
        } else {
            pcm32Bit.toDouble() / Int.MAX_VALUE
        }
}
