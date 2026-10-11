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

/**
 * 同族整数倍插值的复核。
 *
 * 这条链路的承诺只有一条：**原样本逐位保留**——多出来的样本是插值补的，原有的一个也不动。故用例围绕
 * 「原样本是否原样落在偶位」「插值样本是否只由邻居决定」构造，另按频谱量一次镜像抑制与通带增益：
 * 影像若漏回来，听感上比系统重采样更差，那是本实现唯一可能比对手更糟的地方。
 *
 * 倍率判定与可插值编码判定一并复核：它们是直出候选的门槛，判错会让档位落到做不出的组合上。
 */
class SameFamilyUpsampleTest {

    private companion object {
        const val FLOAT = C.ENCODING_PCM_FLOAT
        const val SHORT = C.ENCODING_PCM_16BIT

        /** 单声道源采样率：与真机上的 44.1kHz 源一致，倍率与延迟的换算都按它 */
        const val SOURCE_RATE = 44100
    }

    @Test
    fun sameFamilyMultipleOnlyAcceptsSameFamilyPowerOfTwo() {
        // 同族：44.1 族与 48 族各自成族，2 的幂倍
        assertEquals(2, sameFamilyMultiple(SOURCE_RATE, 88200))
        assertEquals(4, sameFamilyMultiple(SOURCE_RATE, 176400))
        assertEquals(8, sameFamilyMultiple(SOURCE_RATE, 352800))
        assertEquals(2, sameFamilyMultiple(48000, 96000))
        assertEquals(4, sameFamilyMultiple(48000, 192000))

        // 同率属于「与源同率」那一档，不算倍率档
        assertNull(sameFamilyMultiple(SOURCE_RATE, SOURCE_RATE))
        // 跨族：44.1→48、44.1→96 都是真正的重采样，宁可交回系统
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
    fun interpolatableEncodingsAreLinearPcmOnly() {
        assertTrue(canInterpolatePcm(FLOAT))
        assertTrue(canInterpolatePcm(SHORT))
        assertTrue(canInterpolatePcm(C.ENCODING_PCM_24BIT))
        assertTrue(canInterpolatePcm(C.ENCODING_PCM_32BIT))

        assertFalse(canInterpolatePcm(C.ENCODING_PCM_8BIT))
        assertFalse(canInterpolatePcm(C.ENCODING_PCM_16BIT_BIG_ENDIAN))
        assertFalse(canInterpolatePcm(C.ENCODING_PCM_FLOAT_BIG_ENDIAN))
        assertFalse(canInterpolatePcm(C.ENCODING_INVALID))
    }

    // 原样本必须原样落在偶位上，且位置只由「半程抽头」的延迟决定——错一位整个信号就错位
    @Test
    fun originalSamplesLandBitExactOnEvenPositions() {
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

    // 级联两级（×4）之后，原样本仍逐位落在每第 4 个样点上
    @Test
    fun cascadeKeepsOriginalsOnEveryFourthSample() {
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

    // 常值输入下插值样本必须等于原值：插值相位的直流增益不是 1，插值样本就会带上固定偏置
    @Test
    fun constantInputProducesConstantOutput() {
        val frames = 400
        val level = 0.25
        val input = DoubleArray(frames) { level }

        val output = upsample(input, channels = 1, multiple = 2, withTail = true)

        val delay = SameFamilyUpsampler(2).drainFrames / 2
        // 只查整窗都在真实输入之内的那一段：两端各有一段由零历史或静音尾插出来的边界样本
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

    // 状态必须跨缓冲连续：分块喂入与整块喂入的结果要逐位相同，否则每次缓冲边界都会有一道接缝
    @Test
    fun chunkedInputMatchesWholeInput() {
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

    /**
     * 镜像抑制、通带增益，以及「内容频率不许变」。
     *
     * 18.9kHz 的正弦升到 88.2kHz 后，基频仍在 18.9kHz——升采样只改样点密度，不改内容频率；而零插值
     * 带进来的镜像（44.1k−f = 25.2kHz）必须被压下去，否则听感比系统重采样更差。频率翻倍那一项单列：
     * 那正是「把源采样率的帧当成倍率档的帧写出去」的故障表现，是这条链路最该防的错。
     *
     * 取投影而不是取峰值：两个频点在分析窗内都恰好走整数个周期，故窗的起点不影响测得的幅度。
     * 三项一起看才有意义——只压镜像不看通带，把增益做低也能通过。
     */
    @Test
    fun imageIsSuppressedWhileContentFrequencyAndPassbandHold() {
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

    // 打包整型一侧：原样写回要逐位相同，插值样本要贴着理想值——读入、插值、按位深取整整条路径都得对。
    // 用平滑的 1kHz 正弦而不是锯齿：锯齿不是带限信号，任何插值器在阶跃处都有振铃，那验不出对错
    @Test
    fun packedIntPassthroughIsBitExactAndInterpolationTracksIdeal() {
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
        // 只查整窗都在真实输入之内的那一段：起头那几十个样点的历史是零（延迟线尚未灌满），
        // 滤波器对那道「无中生有」的阶跃必有振铃，那不是缺陷
        for (frame in (2 * delay) until (frames - delay)) {
            assertEquals(
                "原样本 $frame 未逐位保留",
                input[frame].toInt(),
                output[(frame + delay) * 2].toInt(),
            )
            // 插值点落在 x[frame] 与 x[frame+1] 之间，内容时刻为 (frame + 0.5) 个源样点
            val ideal = amplitude * sin(2 * PI * frequency * (frame + 0.5) / SOURCE_RATE)
            val interpolated = output[(frame + delay) * 2 + 1].toInt()
            assertTrue(
                "插值样本 $frame 偏离理想值：$interpolated vs $ideal",
                abs(interpolated - ideal) < 4.0,
            )
        }
    }

    /** 按 [multiple] 倍率插值一段浮点样点，[withTail] 时把曲末冲段一并接上 */
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

    /** [frequency] 在 [frames] 个样点上的投影幅度（该频率须在窗内走整数个周期） */
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

    // 取值落在 (-1,1) 内的确定性序列：够杂即可，不必随机源，重跑结果要一致
    private fun randomSamples(size: Int): DoubleArray {
        var state = 0x2545F4914F6CDD1DL
        return DoubleArray(size) {
            state = state * 6364136223846793005L + 1442695040888963407L
            ((state ushr 11).toDouble() / (1L shl 53).toDouble()) - 0.5
        }
    }
}
