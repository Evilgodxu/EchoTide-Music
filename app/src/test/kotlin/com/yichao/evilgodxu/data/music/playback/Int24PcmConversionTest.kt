package com.yichao.evilgodxu.data.music.playback

import androidx.media3.common.C
import androidx.media3.exoplayer.audio.AudioSink
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * 24 位整型转换复核。
 *
 * 自研输出实现全靠这一层把解码数据对齐到设备唯一声明的 24 位整型。此路径在设备上无从直接校验：
 * 数值错一位不会报错，只会让写出编码与所下发的混音器属性对不上，独默走回系统混音；而读取越界会让
 * 播放直接失败（曾因此崩过两次）。故逐项核对：位深只增不减的编码必须逐位精确，越界必须不可能发生，
 * 样本数必须守恒。
 */
class Int24PcmConversionTest {

    @Test
    fun packedInt24PassesThroughUnchanged() {
        val samples = byteArrayOf(
            0x34, 0x12, 0x56,
            0x00, 0x00, 0x80.toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0x7F,
        )
        assertEquals(samples.toList(), convert(samples, C.ENCODING_PCM_24BIT).toList())
    }

    @Test
    fun sixteenBitExpandsExactlyByShiftingIntoTheHighBytes() {
        val source = littleEndian(6).apply {
            putShort(0)
            putShort(32767)
            putShort(-32768)
            flip()
        }
        val output = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN)

        val leftover = packInt24From(source, output, C.ENCODING_PCM_16BIT)

        assertEquals(0, leftover)
        assertArrayEquals(
            byteArrayOf(
                0x00, 0x00, 0x00, // 0
                0x00, 0xFF.toByte(), 0x7F, // 32767 左移八位
                0x00, 0x00, 0x80.toByte(), // -32768 左移八位即 24 位下限
            ),
            output.array(),
        )
    }

    @Test
    fun trailingBytesThatCannotFormASampleAreSkippedWithoutReadingPastTheLimit() {
        val source = littleEndian(5).apply {
            putInt(0)
            put(0x7F) // 余一字节：不构成一个 32 位样本
            flip()
        }
        val output = ByteBuffer.allocate(3).order(ByteOrder.LITTLE_ENDIAN)

        val leftover = packInt24From(source, output, C.ENCODING_PCM_32BIT)

        assertEquals("余数须被回报，由调用方留痕", 1, leftover)
        assertEquals("输入缓冲须被整块消费，否则同一缓冲会反复从同一位置读出余数", 0, source.remaining())
        assertEquals(3, output.position())
    }

    @Test
    fun floatHalfScaleKeepsTheSameOrderOfMagnitude() {
        val source = littleEndian(4).apply { putFloat(0.5f) }
        source.flip()
        val output = ByteBuffer.allocate(3).order(ByteOrder.LITTLE_ENDIAN)

        packInt24From(source, output, C.ENCODING_PCM_FLOAT)

        // 浮点中间态按 0x7FFFFFFF 缩放，半量程处允许一个最低有效位的偏差
        val value = readPackedInt24(output)
        assertTrue("半量程还原值 $value 偏离 0.5 满量程过多", abs(value - 4194304) <= 1)
    }

    @Test
    fun conversionKeepsTheSampleCount() {
        val source = littleEndian(16).apply {
            repeat(4) { putFloat(0.25f) }
            flip()
        }
        val output = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals(0, packInt24From(source, output, C.ENCODING_PCM_FLOAT))

        assertEquals(4, output.position() / 3)
    }

    @Test
    fun floatFormatIsSupportedDirectly() {
        assertEquals(
            AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY,
            int24FormatSupport(C.ENCODING_PCM_FLOAT),
        )
    }

    @Test
    fun int16FormatRequiresTranscoding() {
        assertEquals(
            AudioSink.SINK_FORMAT_SUPPORTED_WITH_TRANSCODING,
            int24FormatSupport(C.ENCODING_PCM_16BIT),
        )
    }

    @Test
    fun int24FormatRequiresTranscoding() {
        assertEquals(
            AudioSink.SINK_FORMAT_SUPPORTED_WITH_TRANSCODING,
            int24FormatSupport(C.ENCODING_PCM_24BIT),
        )
    }

    @Test
    fun nonLinearPcmIsUnsupported() {
        assertEquals(
            AudioSink.SINK_FORMAT_UNSUPPORTED,
            int24FormatSupport(C.ENCODING_INVALID),
        )
    }

    private fun convert(samples: ByteArray, inputEncoding: Int): ByteArray {
        val source = littleEndian(samples.size).apply { put(samples) }
        source.flip()
        val output = ByteBuffer.allocate(samples.size / 3 * 3 + 3).order(ByteOrder.LITTLE_ENDIAN)
        packInt24From(source, output, inputEncoding)
        return ByteArray(output.position()).also { output.rewind(); output.get(it) }
    }

    // 24 位整型按小端三字节存放，与平台编码一致
    private fun readPackedInt24(buffer: ByteBuffer): Int {
        val bytes = buffer.array()
        val unsigned = (bytes[0].toInt() and 0xFF) or
            ((bytes[1].toInt() and 0xFF) shl 8) or
            ((bytes[2].toInt() and 0xFF) shl 16)
        return if (unsigned and 0x800000 != 0) unsigned - 0x1000000 else unsigned
    }

    // 线性 PCM 各编码均按小端存放，与设备上实际字节序一致
    private fun littleEndian(capacity: Int): ByteBuffer =
        ByteBuffer.allocate(capacity).order(ByteOrder.LITTLE_ENDIAN)
}
