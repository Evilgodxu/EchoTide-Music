package com.yichao.evilgodxu.data.music.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

// 奇数相位抽头数，决定延迟与过渡带
private const val PHASE_TAPS = 96

// Kaiser 窗参数，阻带约 -100dB
private const val KAISER_BETA = 10.0

// 级数项数，β=10 时已收敛到双精度极限
private const val BESSEL_TERMS = 25

/** 同族倍率：整除且为 2 的幂，上限 MAX_MULTIPLE */
internal fun sameFamilyMultiple(sourceSampleRate: Int, declaredSampleRate: Int): Int? {
    if (sourceSampleRate <= 0 || declaredSampleRate <= sourceSampleRate) return null
    if (declaredSampleRate % sourceSampleRate != 0) return null
    val multiple = declaredSampleRate / sourceSampleRate
    if (multiple > SameFamilyUpsampler.MAX_MULTIPLE) return null
    if (multiple and (multiple - 1) != 0) return null
    return multiple
}

/** 可插值的线性 PCM：浮点与 16/24/32 位整型 */
internal fun canInterpolatePcm(encoding: Int): Boolean = when (encoding) {
    C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT -> true
    else -> false
}

/**
 * 同族倍率插值：设备只声明 88.2k 时补齐
 * 原样本照抄，非位完美；延迟约 1ms
 * 音频线程运行，不加锁。
 */
@OptIn(UnstableApi::class)
internal class SameFamilyUpsampler(
    /** 倍率：2 的幂 */
    val multiple: Int,
) {

    init {
        require(multiple in 2..MAX_MULTIPLE && multiple and (multiple - 1) == 0) {
            "同族倍率须为 2 的幂且不超过 $MAX_MULTIPLE：$multiple"
        }
    }

    /** 级数，每级 ×2 */
    private val stageCount = Integer.numberOfTrailingZeros(multiple)

    /** 各级延迟线，长 PHASE_TAPS，环同时供两相位读 */
    private var rings = Array(stageCount) { emptyArray<DoubleArray>() }

    /** 各级写出游标 */
    private var cursors = IntArray(stageCount)

    /** 各级落点：第 0 级输入，其后逐级翻倍 */
    private var levels = Array(stageCount + 1) { emptyArray<DoubleArray>() }

    /** 收尾需冲出的源帧数；逐级减半累加，向上取整防丢尾 */
    private val tailFrames: Int =
        (0 until stageCount).sumOf { (DELAY_SAMPLES + (1 shl it) - 1) shr it }

    /** 收尾冲段写出的帧数，恒为 tailFrames×倍率 */
    val drainFrames: Int get() = tailFrames * multiple

    /** 输入帧按倍率展开所需的输出字节数 */
    fun outputBytes(inputFrames: Int, channelCount: Int, encoding: Int): Int =
        inputFrames * multiple * PcmConversion.frameSize(encoding, channelCount)

    /** 读完整块输入写入 output，返回帧数；空间须足够 */
    fun process(input: ByteBuffer, channelCount: Int, encoding: Int, output: ByteBuffer): Int {
        val frames = input.remaining() / PcmConversion.frameSize(encoding, channelCount)
        prepare(channelCount, frames)
        readInterleaved(input, levels[0], frames, channelCount, encoding)
        return interpolateAll(frames, channelCount, encoding, output)
    }

    /** 收尾冲出延迟线尾段，返回写出帧数；输入按静音补齐 */
    fun drain(channelCount: Int, encoding: Int, output: ByteBuffer): Int {
        prepare(channelCount, tailFrames)
        fillSilence(levels[0], tailFrames, channelCount)
        return interpolateAll(tailFrames, channelCount, encoding, output)
    }

    /** 复位延迟线与游标，须清内容以免上条流样点混入 */
    fun reset() {
        cursors.fill(0)
        for (stage in 0 until stageCount) {
            for (line in rings[stage]) line.fill(0.0)
        }
    }

    private fun prepare(channelCount: Int, frames: Int) {
        if (channelCount <= 0) return
        for (stage in 0 until stageCount) {
            val current = rings[stage]
            if (current.size != channelCount) {
                rings[stage] = Array(channelCount) { DoubleArray(PHASE_TAPS) }
            }
        }
        for (level in levels.indices) {
            val levelFrames = frames shl level
            val current = levels[level]
            if (current.size != channelCount || current[0].size < levelFrames) {
                levels[level] = Array(channelCount) { DoubleArray(levelFrames) }
            }
        }
    }

    private fun interpolateAll(
        frames: Int,
        channelCount: Int,
        encoding: Int,
        output: ByteBuffer,
    ): Int {
        var produced = frames
        for (stage in 0 until stageCount) {
            produced = interpolate(stage, levels[stage], levels[stage + 1], produced, channelCount)
        }
        writeInterleaved(levels[stageCount], output, produced, channelCount, encoding)
        return produced
    }

    /** 一级 ×2：偶数位照抄，奇数位插值；两相位延迟须一致 */
    private fun interpolate(
        stage: Int,
        source: Array<DoubleArray>,
        dest: Array<DoubleArray>,
        frames: Int,
        channelCount: Int,
    ): Int {
        val ring = rings[stage]
        val ringSize = ring[0].size
        var cursor = cursors[stage]
        for (frame in 0 until frames) {
            for (channel in 0 until channelCount) {
                val line = ring[channel]
                line[cursor] = source[channel][frame]
                var odd = 0.0
                var index = cursor
                for (tap in phaseCoefficients.indices) {
                    odd += phaseCoefficients[tap] * line[index]
                    index = if (index == 0) ringSize - 1 else index - 1
                }
                dest[channel][2 * frame] = line[(cursor - DELAY_SAMPLES + ringSize) % ringSize]
                dest[channel][2 * frame + 1] = odd
            }
            cursor = if (cursor + 1 == ringSize) 0 else cursor + 1
        }
        cursors[stage] = cursor
        return frames * 2
    }

    private fun readInterleaved(
        source: ByteBuffer,
        dest: Array<DoubleArray>,
        frames: Int,
        channelCount: Int,
        encoding: Int,
    ) {
        for (frame in 0 until frames) {
            for (channel in 0 until channelCount) {
                dest[channel][frame] = readSample(source, encoding)
            }
        }
    }

    private fun writeInterleaved(
        source: Array<DoubleArray>,
        dest: ByteBuffer,
        frames: Int,
        channelCount: Int,
        encoding: Int,
    ) {
        for (frame in 0 until frames) {
            for (channel in 0 until channelCount) {
                writeSample(dest, encoding, source[channel][frame])
            }
        }
    }

    private fun fillSilence(dest: Array<DoubleArray>, frames: Int, channelCount: Int) {
        for (channel in 0 until channelCount) dest[channel].fill(0.0, 0, frames)
    }

    // 读入用双精度，32 位整型无损
    private fun readSample(source: ByteBuffer, encoding: Int): Double =
        if (encoding == C.ENCODING_PCM_FLOAT) {
            source.float.toDouble()
        } else {
            PcmConversion.readAs32BitIntPcm(source, encoding).toDouble()
        }

    private fun writeSample(dest: ByteBuffer, encoding: Int, value: Double) {
        if (encoding == C.ENCODING_PCM_FLOAT) {
            dest.putFloat(value.toFloat())
        } else {
            PcmConversion.write32BitIntPcm(dest, quantize(value, encoding), encoding)
        }
    }

    /** 按位深取整：截断会带半位负偏置，取整误差在 ±半位内 */
    private fun quantize(value: Double, encoding: Int): Int {
        val step = when (encoding) {
            C.ENCODING_PCM_16BIT -> 1 shl 16
            C.ENCODING_PCM_24BIT -> 1 shl 8
            else -> 1
        }
        return ((value / step).roundToLong() * step)
            .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
            .toInt()
    }

    companion object {
        /** 倍率上限，更高无实际设备 */
        const val MAX_MULTIPLE = 8

        /** 半带延迟即半程抽头 */
        private const val DELAY_SAMPLES = PHASE_TAPS / 2

        /** 奇数相位系数，各级复用 */
        private val phaseCoefficients: DoubleArray by lazy { buildPhaseCoefficients() }
    }
}

/** 奇数相位系数：加窗 sinc 半样本偏移，归一到 1 */
private fun buildPhaseCoefficients(): DoubleArray {
    val raw = DoubleArray(PHASE_TAPS) { tap ->
        val offset = tap + 0.5 - PHASE_TAPS / 2.0
        sinc(offset) * kaiser(2 * offset / PHASE_TAPS)
    }
    val sum = raw.sum()
    return DoubleArray(PHASE_TAPS) { raw[it] / sum }
}

private fun sinc(t: Double): Double = if (t == 0.0) 1.0 else sin(PI * t) / (PI * t)

/** Kaiser 窗，x 归一化到 ±1 */
private fun kaiser(x: Double): Double =
    besselI0(KAISER_BETA * sqrt(1.0 - x * x)) / besselI0(KAISER_BETA)

/** 零阶修正贝塞尔函数 */
private fun besselI0(x: Double): Double {
    var sum = 1.0
    var term = 1.0
    val half = x / 2
    for (k in 1..BESSEL_TERMS) {
        term *= (half / k) * (half / k)
        sum += term
    }
    return sum
}
