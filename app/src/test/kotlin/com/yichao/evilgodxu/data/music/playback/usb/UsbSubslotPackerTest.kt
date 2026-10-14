package com.yichao.evilgodxu.data.music.playback.usb

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.PcmAudioUtil
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 子槽打包。
 *
 * 判据是**逐字节**的：这类变换写错一位不会报错，只会让声音轻一点、噪一点，听感上很难判定对错。
 * 故这里直接把左对齐 32 位的给定值打进各档子槽核对输出字节，而不是只验证"长度对得上"。
 */
@OptIn(UnstableApi::class)
class UsbSubslotPackerTest {

    // 左对齐 32 位下子槽取的是高字节：子槽 3 丢掉最低字节 0x78，子槽 2 再丢 0x56
    @Test
    fun subslotKeepsTheMostSignificantBytes() {
        assertArrayEquals(bytes(0x78, 0x56, 0x34, 0x12), subslot(0x12345678, 4))
        assertArrayEquals(bytes(0x56, 0x34, 0x12), subslot(0x12345678, 3))
        assertArrayEquals(bytes(0x34, 0x12), subslot(0x12345678, 2))
        assertArrayEquals(bytes(0x12), subslot(0x12345678, 1))
    }

    // 负值必须整字照搬，不能按无符号截断
    @Test
    fun negativeSampleKeepsTwoComplement() {
        assertArrayEquals(bytes(0x00, 0x00, 0x00, 0x80), subslot(Int.MIN_VALUE, 4))
        assertArrayEquals(bytes(0xFF, 0xFF, 0xFF), subslot(-1, 3))
    }

    // 16 位源放进 16 位槽：一位不动。左移补零成 32 位后再取高两字节，恰好还原原值
    @Test
    fun sixteenBitSourceSurvivesASixteenBitSlot() {
        val output = pack(byteBuffer(0x00, 0x40), C.ENCODING_PCM_16BIT, subslotBytes = 2)

        assertArrayEquals(bytes(0x00, 0x40), output)
    }

    // 同一个 16 位源放进更宽的槽：有效位仍在高位、低位补零，依旧逐位无损
    @Test
    fun sixteenBitSourceIsZeroPaddedInAWiderSlot() {
        assertArrayEquals(
            bytes(0x00, 0x00, 0x40),
            pack(byteBuffer(0x00, 0x40), C.ENCODING_PCM_16BIT, subslotBytes = 3),
        )
        assertArrayEquals(
            bytes(0x00, 0x00, 0x00, 0x40),
            pack(byteBuffer(0x00, 0x40), C.ENCODING_PCM_16BIT, subslotBytes = 4),
        )
    }

    // 24 位源放进 24 位槽是最常见的搭配：值必须原样保留
    @Test
    fun twentyFourBitSourceSurvivesAThreeByteSlot() {
        val output = pack(byteBuffer(0x44, 0xAC, 0x00), C.ENCODING_PCM_24BIT, subslotBytes = 3)

        assertArrayEquals(bytes(0x44, 0xAC, 0x00), output)
    }

    // 缓冲长度不是样本整数倍时余数须被消费掉，否则同一缓冲会反复读出同样的余数而卡住
    @Test
    fun leftoverBytesAreConsumedAndReported() {
        val source = byteBuffer(0x00, 0x40, 0x7F)
        val output = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder())

        val leftover = packToSubslot(source, output, C.ENCODING_PCM_16BIT, subslotBytes = 2)

        assertEquals(1, leftover)
        assertEquals("余数须被消费到末尾", source.limit(), source.position())
        assertEquals(2, output.position())
    }

    // 样本按交错顺序逐个处理，不按帧整体搬：多声道下每个样本各自落到自己的槽里
    @Test
    fun interleavedOrderIsPreservedSampleBySample() {
        val output = pack(byteBuffer(0x10, 0x20, 0x30, 0x40), C.ENCODING_PCM_16BIT, subslotBytes = 2)

        assertArrayEquals(bytes(0x10, 0x20, 0x30, 0x40), output)
    }

    // 与 media3 自身的读写约定对齐：源样本先经它读成左对齐 32 位，再取高字节
    @Test
    fun packingAgreesWithMedia3sLeftJustifiedConvention() {
        val source = byteBuffer(0xFF, 0x7F)
        val asInt = PcmAudioUtil.readAs32BitIntPcm(source, C.ENCODING_PCM_16BIT)

        assertArrayEquals(bytes(0xFF, 0x7F), subslot(asInt, 2))
    }

    // 满幅音量不得动到任何一个字节：独占直出的价值就在于原样送出
    @Test
    fun unityVolumeIsBitPerfect() {
        assertArrayEquals(
            bytes(0x00, 0x40),
            pack(byteBuffer(0x00, 0x40), C.ENCODING_PCM_16BIT, subslotBytes = 2, volume = 1f),
        )
    }

    // 半幅：左对齐 32 位下的取值恰好减半，取高字节后也跟着减半
    @Test
    fun volumeHalvesTheSample() {
        assertArrayEquals(
            bytes(0x00, 0x20),
            pack(byteBuffer(0x00, 0x40), C.ENCODING_PCM_16BIT, subslotBytes = 2, volume = 0.5f),
        )
    }

    // 零音量即静音：整段输出全零，不留残值
    @Test
    fun zeroVolumeSilencesTheSample() {
        assertArrayEquals(
            bytes(0x00, 0x00),
            pack(byteBuffer(0x00, 0x40), C.ENCODING_PCM_16BIT, subslotBytes = 2, volume = 0f),
        )
    }

    // 超过满幅的音量一律按满幅处理：放大没有意义，而越过整型边界会变成削顶失真
    @Test
    fun overUnityVolumeClampsInsteadOfClipping() {
        assertArrayEquals(
            bytes(0x00, 0x40),
            pack(byteBuffer(0x00, 0x40), C.ENCODING_PCM_16BIT, subslotBytes = 2, volume = 4f),
        )
    }

    // 满幅负值在任何音量下都不该翻正：移位必须保持补码语义
    // 源 0xC000（16 位补码 -16384）半幅即 -8192，落在 16 位槽里是 0xE000
    @Test
    fun negativeSampleStaysNegativeUnderVolume() {
        assertArrayEquals(
            bytes(0x00, 0xE0),
            pack(byteBuffer(0x00, 0xC0), C.ENCODING_PCM_16BIT, subslotBytes = 2, volume = 0.5f),
        )
    }

    // 定点增益的边界取值：满幅及以上一律满幅，负音量按零处理
    @Test
    fun gainEncodingCoversTheBoundaries() {
        assertEquals(UNITY_GAIN_Q15, volumeToGainQ15(1f))
        assertEquals(UNITY_GAIN_Q15, volumeToGainQ15(2f))
        assertEquals(0, volumeToGainQ15(0f))
        assertEquals(0, volumeToGainQ15(-1f))
        assertEquals(UNITY_GAIN_Q15 / 2, volumeToGainQ15(0.5f))
    }

    private fun subslot(value: Int, subslotBytes: Int): ByteArray {
        val buffer = ByteBuffer.allocate(subslotBytes).order(ByteOrder.nativeOrder())
        writeSubslot(buffer, value, subslotBytes)
        return buffer.array()
    }

    private fun pack(
        source: ByteBuffer,
        encoding: Int,
        subslotBytes: Int,
        volume: Float = 1f,
    ): ByteArray {
        // 容量按最坏情况给足：每个源字节最多产出一个输出字节
        val output = ByteBuffer.allocate(source.remaining() * subslotBytes).order(ByteOrder.nativeOrder())
        packToSubslot(source, output, encoding, subslotBytes, volume)
        val written = ByteArray(output.position())
        output.flip()
        output.get(written)
        return written
    }

    private fun byteBuffer(vararg values: Int): ByteBuffer {
        val buffer = ByteBuffer.allocate(values.size).order(ByteOrder.nativeOrder())
        values.forEach { buffer.put(it.toByte()) }
        buffer.flip()
        return buffer
    }
}

private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
