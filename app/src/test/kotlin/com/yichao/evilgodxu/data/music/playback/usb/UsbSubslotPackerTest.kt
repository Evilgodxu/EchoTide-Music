package com.yichao.evilgodxu.data.music.playback.usb

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import com.yichao.evilgodxu.data.music.playback.PcmConversion
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 逐字节核对各档子槽的打包结果 */
@OptIn(UnstableApi::class)
class UsbSubslotPackerTest {

    // 左对齐取高字节：子槽 3 丢 0x78
    @Test
    fun `子槽保留最高有效字节`() {
        assertArrayEquals(bytes(0x78, 0x56, 0x34, 0x12), subslot(0x12345678, 4))
        assertArrayEquals(bytes(0x56, 0x34, 0x12), subslot(0x12345678, 3))
        assertArrayEquals(bytes(0x34, 0x12), subslot(0x12345678, 2))
        assertArrayEquals(bytes(0x12), subslot(0x12345678, 1))
    }

    // 负值必须整字照搬，不能按无符号截断
    @Test
    fun `负样本保持补码`() {
        assertArrayEquals(bytes(0x00, 0x00, 0x00, 0x80), subslot(Int.MIN_VALUE, 4))
        // -0x1000 为 LSB 整数倍，取整不动
        assertArrayEquals(bytes(0xF0, 0xFF, 0xFF), subslot(-0x1000, 3))
    }

    // 丢低位用四舍五入，半个 LSB 在 0x80
    @Test
    fun `丢低位按目标子槽四舍五入`() {
        assertArrayEquals(bytes(0x00, 0x00, 0x00), subslot(0x7F, 3))
        assertArrayEquals(bytes(0x01, 0x00, 0x00), subslot(0x80, 3))
        assertArrayEquals(bytes(0x02, 0x00, 0x00), subslot(0x180, 3))
        assertArrayEquals(bytes(0xFF, 0xFF, 0xFF), subslot(-0x81, 3))
    }

    // 窄源进宽槽才留得住深衰减细节
    @Test
    fun `深衰减的细节在更宽的子槽中保留`() {
        assertArrayEquals(
            bytes(0x40, 0x00, 0x00),
            pack(byteBuffer(0x01, 0x00), C.ENCODING_PCM_16BIT, subslotBytes = 3, volume = 0.25f),
        )
        assertArrayEquals(
            bytes(0x00, 0x00),
            pack(byteBuffer(0x01, 0x00), C.ENCODING_PCM_16BIT, subslotBytes = 2, volume = 0.25f),
        )
    }

    // 16 位进 16 位槽，逐位不动
    @Test
    fun `16 位源在 16 位子槽中原样通过`() {
        val output = pack(byteBuffer(0x00, 0x40), C.ENCODING_PCM_16BIT, subslotBytes = 2)

        assertArrayEquals(bytes(0x00, 0x40), output)
    }

    // 进更宽槽仍逐位无损
    @Test
    fun `16 位源在更宽子槽中零填充`() {
        assertArrayEquals(
            bytes(0x00, 0x00, 0x40),
            pack(byteBuffer(0x00, 0x40), C.ENCODING_PCM_16BIT, subslotBytes = 3),
        )
        assertArrayEquals(
            bytes(0x00, 0x00, 0x00, 0x40),
            pack(byteBuffer(0x00, 0x40), C.ENCODING_PCM_16BIT, subslotBytes = 4),
        )
    }

    // 24 位进 24 位槽须原样保留
    @Test
    fun `24 位源在三字节子槽中原样通过`() {
        val output = pack(byteBuffer(0x44, 0xAC, 0x00), C.ENCODING_PCM_24BIT, subslotBytes = 3)

        assertArrayEquals(bytes(0x44, 0xAC, 0x00), output)
    }

    // 余数须消费，否则卡住
    @Test
    fun `剩余字节被消费并上报`() {
        val source = byteBuffer(0x00, 0x40, 0x7F)
        val output = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder())

        val leftover = packToSubslot(source, output, C.ENCODING_PCM_16BIT, subslotBytes = 2)

        assertEquals(1, leftover)
        assertEquals("余数须被消费到末尾", source.limit(), source.position())
        assertEquals(2, output.position())
    }

    // 逐样本交错处理，各入其槽
    @Test
    fun `交错顺序逐样本保持`() {
        val output = pack(byteBuffer(0x10, 0x20, 0x30, 0x40), C.ENCODING_PCM_16BIT, subslotBytes = 2)

        assertArrayEquals(bytes(0x10, 0x20, 0x30, 0x40), output)
    }

    // 经 media3 读成左对齐 32 位
    @Test
    fun `打包与 media3 的左对齐约定一致`() {
        val source = byteBuffer(0xFF, 0x7F)
        val asInt = PcmConversion.readAs32BitIntPcm(source, C.ENCODING_PCM_16BIT)

        assertArrayEquals(bytes(0xFF, 0x7F), subslot(asInt, 2))
    }

    // 满幅不动任何字节
    @Test
    fun `增益为 1 时位精确`() {
        assertArrayEquals(
            bytes(0x00, 0x40),
            pack(byteBuffer(0x00, 0x40), C.ENCODING_PCM_16BIT, subslotBytes = 2, volume = 1f),
        )
    }

    // 半幅取值减半
    @Test
    fun `音量把样本减半`() {
        assertArrayEquals(
            bytes(0x00, 0x20),
            pack(byteBuffer(0x00, 0x40), C.ENCODING_PCM_16BIT, subslotBytes = 2, volume = 0.5f),
        )
    }

    // 零音量整段全零
    @Test
    fun `音量为零时样本静音`() {
        assertArrayEquals(
            bytes(0x00, 0x00),
            pack(byteBuffer(0x00, 0x40), C.ENCODING_PCM_16BIT, subslotBytes = 2, volume = 0f),
        )
    }

    // 超满幅按满幅，防削顶
    @Test
    fun `超过 1 的增益钳制而非削波`() {
        assertArrayEquals(
            bytes(0x00, 0x40),
            pack(byteBuffer(0x00, 0x40), C.ENCODING_PCM_16BIT, subslotBytes = 2, volume = 4f),
        )
    }

    // 满幅负值不翻正，移位保持补码
    @Test
    fun `负样本在音量下仍为负`() {
        assertArrayEquals(
            bytes(0x00, 0xE0),
            pack(byteBuffer(0x00, 0xC0), C.ENCODING_PCM_16BIT, subslotBytes = 2, volume = 0.5f),
        )
    }

    // 满幅及以上取满幅，负音量取零
    @Test
    fun `增益编码覆盖边界值`() {
        assertEquals(UNITY_GAIN_Q15, volumeToGainQ15(1f))
        assertEquals(UNITY_GAIN_Q15, volumeToGainQ15(2f))
        assertEquals(0, volumeToGainQ15(0f))
        assertEquals(0, volumeToGainQ15(-1f))
        assertEquals(UNITY_GAIN_Q15 / 2, volumeToGainQ15(0.5f))
    }

    // 起止相同即整段等增益
    @Test
    fun `增益相等时不产生渐变`() {
        assertArrayEquals(
            pack(byteBuffer(0x00, 0x40, 0x00, 0x40), C.ENCODING_PCM_16BIT, subslotBytes = 2, volume = 0.5f),
            packRamped(
                byteBuffer(0x00, 0x40, 0x00, 0x40),
                C.ENCODING_PCM_16BIT,
                subslotBytes = 2,
                gainFromQ15 = UNITY_GAIN_Q15 / 2,
            ),
        )
    }

    // 首样本在起始增益，末样本恰在目标
    @Test
    fun `渐变从起点开始且精确落在目标`() {
        val samples = 5
        val source = ByteBuffer.allocate(samples * 2).order(ByteOrder.nativeOrder())
        repeat(samples) { source.putShort(0x7FFF) }
        source.flip()

        val output = packRamped(
            source,
            C.ENCODING_PCM_16BIT,
            subslotBytes = 2,
            gainFromQ15 = UNITY_GAIN_Q15 / 2,
            gainToQ15 = UNITY_GAIN_Q15,
        )

        // 半幅 16383.5 进位 16384
        assertEquals(16384, shortAt(output, 0))
        // 末样本满幅，一位不动
        assertEquals(32767, shortAt(output, samples - 1))
    }

    // 逐样本单调不减
    @Test
    fun `渐变单调`() {
        val samples = 16
        val source = ByteBuffer.allocate(samples * 2).order(ByteOrder.nativeOrder())
        repeat(samples) { source.putShort(0x7FFF) }
        source.flip()

        val output = packRamped(
            source,
            C.ENCODING_PCM_16BIT,
            subslotBytes = 2,
            gainFromQ15 = UNITY_GAIN_Q15 / 4,
            gainToQ15 = UNITY_GAIN_Q15,
        )
        val values = (0 until samples).map { shortAt(output, it) }

        assertEquals(values.sorted(), values)
    }

    // 逐样本插值，第二个仍在中间
    @Test
    fun `渐变逐样本插值`() {
        val samples = 3
        val source = ByteBuffer.allocate(samples * 2).order(ByteOrder.nativeOrder())
        repeat(samples) { source.putShort(0x7FFF) }
        source.flip()

        val output = packRamped(
            source,
            C.ENCODING_PCM_16BIT,
            subslotBytes = 2,
            gainFromQ15 = 0,
            gainToQ15 = UNITY_GAIN_Q15,
        )

        assertEquals(0, shortAt(output, 0))
        // 16383.5 进位
        assertEquals(16384, shortAt(output, 1))
        assertEquals(32767, shortAt(output, samples - 1))
    }

    // 负样本不翻正，按补码缩放
    @Test
    fun `渐变保持负样本为负`() {
        val samples = 4
        val source = ByteBuffer.allocate(samples * 2).order(ByteOrder.nativeOrder())
        repeat(samples) { source.putShort(-16384) }
        source.flip()

        val output = packRamped(
            source,
            C.ENCODING_PCM_16BIT,
            subslotBytes = 2,
            gainFromQ15 = UNITY_GAIN_Q15 / 2,
            gainToQ15 = UNITY_GAIN_Q15,
        )

        assertTrue((0 until samples).all { shortAt(output, it) < 0 })
    }

    // 定长斜坡只在前 N 个样本走完
    @Test
    fun `定长斜坡在指定样本数内走完`() {
        val samples = 8
        val source = ByteBuffer.allocate(samples * 2).order(ByteOrder.nativeOrder())
        repeat(samples) { source.putShort(0x7FFF) }
        source.flip()

        val output = packRamped(
            source,
            C.ENCODING_PCM_16BIT,
            subslotBytes = 2,
            gainFromQ15 = 0,
            gainToQ15 = UNITY_GAIN_Q15,
            rampSamples = 3,
        )

        val values = (0 until samples).map { shortAt(output, it) }
        assertEquals(0, values[0])
        // 乘 0.5 得 16383.5 进位
        assertEquals(16384, values[1])
        // 下标 2 即到目标
        assertTrue(values.drop(2).all { it == 32767 })
    }

    // 给 0 即不渐变
    @Test
    fun `定长斜坡为零时直接取目标增益`() {
        val samples = 4
        val source = ByteBuffer.allocate(samples * 2).order(ByteOrder.nativeOrder())
        repeat(samples) { source.putShort(0x7FFF) }
        source.flip()

        val output = packRamped(
            source,
            C.ENCODING_PCM_16BIT,
            subslotBytes = 2,
            gainFromQ15 = 0,
            gainToQ15 = UNITY_GAIN_Q15,
            rampSamples = 0,
        )

        assertArrayEquals(bytes(0xFF, 0x7F, 0xFF, 0x7F, 0xFF, 0x7F, 0xFF, 0x7F), output)
    }

    private fun shortAt(bytes: ByteArray, index: Int): Int =
        (bytes[index * 2].toInt() and 0xFF) or (bytes[index * 2 + 1].toInt() shl 8)

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
    ): ByteArray = packRamped(source, encoding, subslotBytes, volumeToGainQ15(volume))

    private fun packRamped(
        source: ByteBuffer,
        encoding: Int,
        subslotBytes: Int,
        gainFromQ15: Int,
        gainToQ15: Int = gainFromQ15,
        rampSamples: Int = RAMP_WHOLE_BLOCK,
    ): ByteArray {
        // 按最坏情况每个源字节一字节
        val output = ByteBuffer.allocate(source.remaining() * subslotBytes).order(ByteOrder.nativeOrder())
        packToSubslot(source, output, encoding, subslotBytes, gainFromQ15, gainToQ15, rampSamples)
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
