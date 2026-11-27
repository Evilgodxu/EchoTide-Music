package com.yichao.evilgodxu.data.music.playback

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** 原样本逐位保留；插值只由邻居决定 */
class SameFamilyUpsampleTest {

    private companion object {
        const val FLOAT = C.ENCODING_PCM_FLOAT
        const val SHORT = C.ENCODING_PCM_16BIT

        /** 单声道 44.1kHz 源采样率 */
        const val SOURCE_RATE = 44100
    }

    @Test
    fun `同族倍率只接受二的幂`() {
        // 44.1/48 两族，2 的幂倍
        assertEquals(2, sameFamilyMultiple(SOURCE_RATE, 88200))
        assertEquals(4, sameFamilyMultiple(SOURCE_RATE, 176400))
        assertEquals(8, sameFamilyMultiple(SOURCE_RATE, 352800))
        assertEquals(2, sameFamilyMultiple(48000, 96000))
        assertEquals(4, sameFamilyMultiple(48000, 192000))

        // 同率不算倍率档
        assertNull(sameFamilyMultiple(SOURCE_RATE, SOURCE_RATE))
        // 跨族是真重采样，交回系统
        assertNull(sameFamilyMultiple(SOURCE_RATE, 48000))
        assertNull(sameFamilyMultiple(SOURCE_RATE, 96000))
        // 同族但不是 2 的幂，同样不做
        assertNull(sameFamilyMultiple(SOURCE_RATE, 132300))
        // 超过上限：数据量与延迟都成倍往上翻
        assertNull(sameFamilyMultiple(SOURCE_RATE, 705600))
        assertNull(sameFamilyMultiple(0, 88200))
        // 降率不在直出之列
        assertNull(sameFamilyMultiple(48000, 24000))
    }

    @Test
    fun `可插值编码仅限线性 PCM`() {
        assertTrue(canInterpolatePcm(FLOAT))
        assertTrue(canInterpolatePcm(SHORT))
        assertTrue(canInterpolatePcm(C.ENCODING_PCM_24BIT))
        assertTrue(canInterpolatePcm(C.ENCODING_PCM_32BIT))

        assertFalse(canInterpolatePcm(C.ENCODING_PCM_8BIT))
        assertFalse(canInterpolatePcm(C.ENCODING_PCM_16BIT_BIG_ENDIAN))
        assertFalse(canInterpolatePcm(C.ENCODING_PCM_FLOAT_BIG_ENDIAN))
        assertFalse(canInterpolatePcm(C.ENCODING_INVALID))
    }

    // 原样本落偶位，位置由半程延迟定
    @Test
    fun `原始样本在位精确落在偶数位`() {
        val channels = 2
        val frames = 257
        val input = randomSamples(frames * channels)
        val multiple = 2

        val output = upsample(input, channels, multiple, withTail = true)

        val delay = SameFamilyUpsampler(multiple).drainFrames / multiple
        assertEquals((frames + delay) * multiple * channels, output.size)
        for (frame in 0 until frames) {
            for (channel in 0 until channels) {
                assertEquals(
                    "原样本 $frame/$channel 未逐位保留",
                    input[frame * channels + channel].toFloat().toDouble(),
                    output[(frame + delay) * multiple * channels + channel],
                    0.0,
                )
            }
        }
    }

    // ×4 后仍落在每第 4 个样点
    @Test
    fun `级联后每第四点仍是原始样本`() {
        val channels = 1
        val frames = 199
        val input = randomSamples(frames)
        val multiple = 4

        val output = upsample(input, channels, multiple, withTail = true)

        val delay = SameFamilyUpsampler(multiple).drainFrames / multiple
        assertEquals((frames + delay) * multiple, output.size)
        for (frame in 0 until frames) {
            assertEquals(
                "级联后原样本 $frame 未逐位保留",
                input[frame].toFloat().toDouble(),
                output[(frame + delay) * multiple],
                0.0,
            )
        }
    }

        // 常值输入插值须等于原值
    @Test
    fun `恒值输入产出恒值输出`() {
        val frames = 400
        val level = 0.25
        val input = DoubleArray(frames) { level }

        val output = upsample(input, channels = 1, multiple = 2, withTail = true)

        val delay = SameFamilyUpsampler(2).drainFrames / 2
        // 只查真实输入之内的整窗
        for (frame in (2 * delay) until (frames - delay)) {
            for (phase in 0 until 2) {
                assertEquals(
                    "样点 ${frame * 2 + phase} 未保持常值",
                    level,
                    output[(frame + delay) * 2 + phase],
                    1e-9,
                )
            }
        }
    }

    // 跨缓冲连续，否则有接缝
    @Test
    fun `分块输入与整块输入一致`() {
        val frames = 1000
        val chunk = 137
        val input = randomSamples(frames)

        val whole = upsample(input, channels = 1, multiple = 2, withTail = true)

        val upsampler = SameFamilyUpsampler(2)
        val assembled = ArrayList<Double>()
        var position = 0
        while (position < frames) {
            val size = minOf(chunk, frames - position)
            val piece = input.copyOfRange(position, position + size)
            assembled.addAll(upsamplePiece(upsampler, piece, channels = 1, multiple = 2).asIterable())
            position += size
        }
        assembled.addAll(drainPiece(upsampler, channels = 1, multiple = 2).asIterable())

        assertEquals(whole.size, assembled.size)
        for (index in whole.indices) {
            assertEquals("第 $index 个样点分块后不一致", whole[index], assembled[index], 0.0)
        }
    }

    /** 核验镜像抑制、通带增益与内容频率不变 */
    @Test
    fun `镜像被压制且内容频率与通带保持`() {
        val tone = 18900.0
        val frames = SOURCE_RATE * 3
        val input = DoubleArray(frames) { 0.5 * cos(2 * PI * tone * it / SOURCE_RATE) }

        val output = upsample(input, channels = 1, multiple = 2, withTail = true)

        val outputRate = SOURCE_RATE * 2
        val windowFrames = outputRate * 2
        val start = SameFamilyUpsampler(2).drainFrames + 4096
        val fundamental = amplitudeAt(output, start, windowFrames, outputRate, tone)
        val image = amplitudeAt(output, start, windowFrames, outputRate, outputRate / 2 - tone)
        val doubled = amplitudeAt(output, start, windowFrames, outputRate, tone * 2)

        assertEquals("通带增益应保持 0.5", 0.5, fundamental, 0.005)
        val attenuationDb = 20 * log10(image / fundamental)
        assertTrue("镜像抑制不足：${attenuationDb}dB", attenuationDb < -80)
        assertTrue("出现频率翻倍的能量（源帧被当成了倍率档的帧）：$doubled", doubled < fundamental / 1000)
    }

    // 原样写回逐位相同，插值贴理想值
    // 用 1kHz 正弦，锯齿有振铃验不准
    @Test
    fun `打包整型直通位精确且插值贴合理想`() {
        val frames = 2000
        val amplitude = 10000.0
        val frequency = 1000.0
        val input = ShortArray(frames) {
            (amplitude * sin(2 * PI * frequency * it / SOURCE_RATE)).roundToInt().toShort()
        }
        val upsampler = SameFamilyUpsampler(2)

        val source = ByteBuffer.allocateDirect(frames * 2).order(ByteOrder.nativeOrder())
        for (value in input) source.putShort(value)
        source.flip()
        val dest = ByteBuffer.allocateDirect(upsampler.outputBytes(frames, 1, SHORT))
            .order(ByteOrder.nativeOrder())
        val produced = upsampler.process(source, 1, SHORT, dest)
        dest.flip()
        val output = ShortArray(produced) { dest.getShort() }

        val delay = upsampler.drainFrames / 2
        // 只查真实输入段，起头历史为零
        for (frame in (2 * delay) until (frames - delay)) {
            assertEquals(
                "原样本 $frame 未逐位保留",
                input[frame].toInt(),
                output[(frame + delay) * 2].toInt(),
            )
            // 插值点在 frame 与 frame+1 间
            val ideal = amplitude * sin(2 * PI * frequency * (frame + 0.5) / SOURCE_RATE)
            val interpolated = output[(frame + delay) * 2 + 1].toInt()
            assertTrue(
                "插值样本 $frame 偏离理想值：$interpolated vs $ideal",
                abs(interpolated - ideal) < 4.0,
            )
        }
    }

    /** 按倍率插值浮点样点，可接曲末冲段 */
    private fun upsample(
        input: DoubleArray,
        channels: Int,
        multiple: Int,
        withTail: Boolean,
    ): DoubleArray {
        val upsampler = SameFamilyUpsampler(multiple)
        val assembled = upsamplePiece(upsampler, input, channels, multiple)
        return if (withTail) assembled + drainPiece(upsampler, channels, multiple) else assembled
    }

    private fun upsamplePiece(
        upsampler: SameFamilyUpsampler,
        input: DoubleArray,
        channels: Int,
        multiple: Int,
    ): DoubleArray {
        val frames = input.size / channels
        val source = ByteBuffer.allocateDirect(input.size * 4).order(ByteOrder.nativeOrder())
        for (value in input) source.putFloat(value.toFloat())
        source.flip()
        val dest = ByteBuffer.allocateDirect(upsampler.outputBytes(frames, channels, FLOAT))
            .order(ByteOrder.nativeOrder())
        val produced = upsampler.process(source, channels, FLOAT, dest)
        dest.flip()
        return DoubleArray(produced * channels) { dest.getFloat().toDouble() }
    }

    private fun drainPiece(
        upsampler: SameFamilyUpsampler,
        channels: Int,
        multiple: Int,
    ): DoubleArray {
        val dest = ByteBuffer.allocateDirect(upsampler.drainFrames * channels * 4)
            .order(ByteOrder.nativeOrder())
        val produced = upsampler.drain(channels, FLOAT, dest)
        dest.flip()
        assertEquals("冲段帧数应等于固有延迟", upsampler.drainFrames, produced)
        return DoubleArray(produced * channels) { dest.getFloat().toDouble() }
    }

    /** 该频率在窗内的投影幅度 */
    private fun amplitudeAt(
        values: DoubleArray,
        start: Int,
        frames: Int,
        sampleRate: Int,
        frequency: Double,
    ): Double {
        var real = 0.0
        var imaginary = 0.0
        for (index in 0 until frames) {
            val phase = 2 * PI * frequency * index / sampleRate
            real += values[start + index] * cos(phase)
            imaginary += values[start + index] * sin(phase)
        }
        return 2 * sqrt(real * real + imaginary * imaginary) / frames
    }

    // 确定性序列，重跑结果一致
    private fun randomSamples(size: Int): DoubleArray {
        var state = 0x2545F4914F6CDD1DL
        return DoubleArray(size) {
            state = state * 6364136223846793005L + 1442695040888963407L
            ((state ushr 11).toDouble() / (1L shl 53).toDouble()) - 0.5
        }
    }
}
