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

/** 位深只增不减须逐位精确，越界不可发生 */
class IntPcmConversionTest {

    private val int24 = C.ENCODING_PCM_24BIT
    private val int32 = C.ENCODING_PCM_32BIT

    @Test
    fun `打包 24 位原样通过`() {
        val samples = byteArrayOf(
            0x34, 0x12, 0x56,
            0x00, 0x00, 0x80.toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0x7F,
        )
        assertEquals(samples.toList(), convert(samples, int24, int24).toList())
    }

    @Test
    fun `打包 32 位原样通过`() {
        val samples = byteArrayOf(
            0x34, 0x12, 0x56, 0x78,
            0x00, 0x00, 0x00, 0x80.toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x7F,
        )
        assertEquals(samples.toList(), convert(samples, int32, int32).toList())
    }

    @Test
    fun `16 位左移进高字节后精确扩展`() {
        val source = littleEndian(6).apply {
            putShort(0)
            putShort(32767)
            putShort(-32768)
            flip()
        }
        val output = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN)

        val leftover = packIntPcm(source, output, C.ENCODING_PCM_16BIT, int24)

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
    fun `16 位精确扩展到 32 位的高半区`() {
        val source = littleEndian(6).apply {
            putShort(0)
            putShort(32767)
            putShort(-32768)
            flip()
        }
        val output = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)

        val leftover = packIntPcm(source, output, C.ENCODING_PCM_16BIT, int32)

        assertEquals(0, leftover)
        assertArrayEquals(
            byteArrayOf(
                0x00, 0x00, 0x00, 0x00, // 0
                0x00, 0x00, 0xFF.toByte(), 0x7F, // 32767 左移十六位
                0x00, 0x00, 0x00, 0x80.toByte(), // -32768 左移十六位即 32 位下限
            ),
            output.array(),
        )
    }

    @Test
    fun `不足一帧的尾字节被跳过且不越过长度上限`() {
        val source = littleEndian(5).apply {
            putInt(0)
            put(0x7F) // 余一字节：不构成一个 32 位样本
            flip()
        }
        val output = ByteBuffer.allocate(3).order(ByteOrder.LITTLE_ENDIAN)

        val leftover = packIntPcm(source, output, C.ENCODING_PCM_32BIT, int24)

        assertEquals("余数须被回报，由调用方留痕", 1, leftover)
        assertEquals("输入缓冲须被整块消费，否则同一缓冲会反复从同一位置读出余数", 0, source.remaining())
        assertEquals(3, output.position())
    }

    @Test
    fun `浮点减半后量级不变`() {
        val source = littleEndian(4).apply { putFloat(0.5f) }
        source.flip()
        val output = ByteBuffer.allocate(3).order(ByteOrder.LITTLE_ENDIAN)

        packIntPcm(source, output, C.ENCODING_PCM_FLOAT, int24)

        // 按 0x7FFFFFFF 缩放，容 1 LSB
        val value = readPackedInt24(output)
        assertTrue("半量程还原值 $value 偏离 0.5 满量程过多", abs(value - 4194304) <= 1)
    }

    @Test
    fun `浮点减半写入 32 位后量级不变`() {
        val source = littleEndian(4).apply { putFloat(0.5f) }
        source.flip()
        val output = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)

        packIntPcm(source, output, C.ENCODING_PCM_FLOAT, int32)

        // 同上，中间态写 32 位整型
        val value = output.order(ByteOrder.LITTLE_ENDIAN).getInt(0)
        assertTrue("半量程还原值 $value 偏离 0.5 满量程过多", abs(value - 1073741823) <= 2)
    }

    @Test
    fun `转换保持样本数`() {
        val source = littleEndian(16).apply {
            repeat(4) { putFloat(0.25f) }
            flip()
        }
        val output = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals(0, packIntPcm(source, output, C.ENCODING_PCM_FLOAT, int24))

        assertEquals(4, output.position() / 3)
    }

    @Test
    fun `浮点格式直接支持`() {
        assertEquals(
            AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY,
            pcmFormatSupport(C.ENCODING_PCM_FLOAT),
        )
    }

    @Test
    fun `16 位格式需要转码`() {
        assertEquals(
            AudioSink.SINK_FORMAT_SUPPORTED_WITH_TRANSCODING,
            pcmFormatSupport(C.ENCODING_PCM_16BIT),
        )
    }

    @Test
    fun `24 位格式需要转码`() {
        assertEquals(
            AudioSink.SINK_FORMAT_SUPPORTED_WITH_TRANSCODING,
            pcmFormatSupport(C.ENCODING_PCM_24BIT),
        )
    }

    @Test
    fun `非线性 PCM 不支持`() {
        assertEquals(
            AudioSink.SINK_FORMAT_UNSUPPORTED,
            pcmFormatSupport(C.ENCODING_INVALID),
        )
    }

    private fun convert(samples: ByteArray, inputEncoding: Int, outputEncoding: Int): ByteArray {
        val source = littleEndian(samples.size).apply { put(samples) }
        source.flip()
        // 每样本至多 4 字节，按四倍申请
        val output = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        packIntPcm(source, output, inputEncoding, outputEncoding)
        return ByteArray(output.position()).also { output.rewind(); output.get(it) }
    }

    // 24 位按小端三字节存放
    private fun readPackedInt24(buffer: ByteBuffer): Int {
        val bytes = buffer.array()
        val unsigned = (bytes[0].toInt() and 0xFF) or
            ((bytes[1].toInt() and 0xFF) shl 8) or
            ((bytes[2].toInt() and 0xFF) shl 16)
        return if (unsigned and 0x800000 != 0) unsigned - 0x1000000 else unsigned
    }

    // 各编码均按小端存放
    private fun littleEndian(capacity: Int): ByteBuffer =
        ByteBuffer.allocate(capacity).order(ByteOrder.LITTLE_ENDIAN)
}