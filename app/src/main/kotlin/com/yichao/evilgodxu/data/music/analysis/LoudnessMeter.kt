package com.yichao.evilgodxu.data.music.analysis

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.tan

/**
 * 一次响度测量的结果。
 *
 * [loudnessLufs] 是门限加权后的整体响度，[gainDb] 已换算成 ReplayGain 2.0 的增益值
 * （参考响度 [REPLAY_GAIN_REFERENCE_LUFS]），[peak] 是线性峰值。
 */
internal data class ReplayGainMeasurement(
    val loudnessLufs: Double,
    val gainDb: Double,
    val peak: Double,
)

/**
 * 流式响度计：按 EBU R128 的 K 加权 + 门限统计整体响度，并记录样本峰值。
 *
 * 只吃「已解交织的浮点样本」，不碰解码与容器，故可脱离设备在单测里用合成波形验证。
 *
 * 分块口径：400ms 一块、每 100ms 出一块（75% 重叠）。门限两级 —— 先按 -70 LUFS 剔除静音，
 * 再按「过绝对门限部分的均值 - 10 LU」剔除偏弱段；两步都用均值而非总和，
 * 否则长曲子的静音段会被计入分母，响度随曲长漂移。
 *
 * 峰值取原始样本的绝对值最大值，不做真峰值过采样：与多数播放器的 ReplayGain 峰值口径一致，
 * 过采样只影响削波提示的严格程度，不影响增益本身。
 */
internal class LoudnessMeter(sampleRate: Int, channels: Int) {

    private val channelCount = channels.coerceAtLeast(1)
    private val blockFrames = (sampleRate * BLOCK_MS / 1_000).coerceAtLeast(1)
    private val stepFrames = (sampleRate * STEP_MS / 1_000).coerceAtLeast(1)
    private val filters = Array(channelCount) { KWeighting(sampleRate) }
    // 逐声道的滑动平方和：写入新样本时减去被覆盖的旧样本，块均值因此不必回读整个窗口
    private val sliding = Array(channelCount) { FloatArray(blockFrames) }
    private var writeIndex = 0
    private var framesSinceStep = 0
    private var framesSeen = 0L
    private var squareSum = 0.0
    private var peak = 0.0
    private val blockLoudnessSquares = ArrayList<Double>()

    /** 消费一段交错浮点样本；[frames] 为采样帧数（不含声道数） */
    fun accept(interleaved: FloatArray, frames: Int) {
        if (frames <= 0) return
        val channels = channelCount
        for (frame in 0 until frames) {
            val base = frame * channels
            for (channel in 0 until channels) {
                val raw = interleaved[base + channel]
                val magnitude = abs(raw).toDouble()
                if (magnitude > peak) peak = magnitude
                val filtered = filters[channel].process(raw)
                val slot = sliding[channel]
                val previous = slot[writeIndex]
                squareSum += filtered.toDouble() * filtered - previous.toDouble() * previous
                slot[writeIndex] = filtered
            }
            writeIndex = (writeIndex + 1) % blockFrames
            framesSeen++
            // 窗口未填满时不统计：不足一块的均值不能与整块并列
            if (framesSeen < blockFrames) continue
            framesSinceStep++
            if (framesSinceStep < stepFrames) continue
            framesSinceStep = 0
            blockLoudnessSquares.add(squareSum / (blockFrames.toDouble() * channelCount))
        }
    }

    /** 门限加权后的整体响度；没有可统计的块（全静音或样本不足一块）时返回 null */
    fun result(): ReplayGainMeasurement? {
        val loudness = integratedLoudness() ?: return null
        return ReplayGainMeasurement(
            loudnessLufs = loudness,
            gainDb = REPLAY_GAIN_REFERENCE_LUFS - loudness,
            peak = peak,
        )
    }

    private fun integratedLoudness(): Double? {
        val absoluteThreshold = 10.0.pow((ABSOLUTE_GATE_LUFS + LOUDNESS_OFFSET) / 10.0)
        val aboveAbsolute = blockLoudnessSquares.filter { it > absoluteThreshold }
        if (aboveAbsolute.isEmpty()) return null
        // 相对门限 = 过绝对门限后的响度 - 10 LU，换算回平方域即乘 0.1
        val relativeThreshold = aboveAbsolute.average() * 10.0.pow(-RELATIVE_GATE_LU / 10.0)
        val aboveRelative = aboveAbsolute.filter { it > relativeThreshold }
        if (aboveRelative.isEmpty()) return null
        val mean = aboveRelative.average()
        if (mean <= 0.0) return null
        return -LOUDNESS_OFFSET + 10.0 * log10(mean)
    }

    private companion object {
        const val BLOCK_MS = 400
        const val STEP_MS = 100
        // 整体响度公式里的常数项：-0.691 dB（单声道/立体声在 0 dBFS 满幅正弦下的偏移）
        const val LOUDNESS_OFFSET = 0.691
        const val ABSOLUTE_GATE_LUFS = -70.0
        const val RELATIVE_GATE_LU = 10.0
    }
}

/**
 * ReplayGain 2.0 的参考响度：增益 = 该值 - 实测整体响度。
 *
 * 同时是 `REPLAYGAIN_REFERENCE_LOUDNESS` 要写入的取值——它是增益的基准，
 * 播放器据此判断这批增益能否直接套用，故不能只留在测量口径里。
 */
internal const val REPLAY_GAIN_REFERENCE_LUFS = -18.0

/**
 * K 加权的两级双二阶滤波：高频搁架 + 高通。
 *
 * 系数按 BS.1770 给出的模拟原型在现场推导（`K = tan(π·f0/fs)`），参数取标准值。
 * 不用 RBJ 的搁架公式：两者在 1 kHz 处差约 0.2 dB，与 foobar2000 等播放器的 ReplayGain
 * 读数就对不上了。采样率取自解码输出而非容器声明值 —— HE-AAC 的 SBR 会让输出翻倍，
 * 系数若按 48k 写死，44.1k 与 96k 的输出都会偏。
 */
private class KWeighting(sampleRate: Int) {

    private val shelf = Biquad.highShelf(
        sampleRate = sampleRate,
        frequency = 1681.974450955533,
        gainDb = 3.999843853973347,
        q = 0.7071752369554196,
    )
    private val highPass = Biquad.highPass(
        sampleRate = sampleRate,
        frequency = 38.13547087602444,
        q = 0.5003270373238773,
    )

    fun process(sample: Float): Float = highPass.process(shelf.process(sample))
}

/** 直接 I 型双二阶：系数在构造时按 a0 归一，逐样本只做乘加与状态移位 */
private class Biquad(
    private val b0: Double,
    private val b1: Double,
    private val b2: Double,
    private val a1: Double,
    private val a2: Double,
) {

    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    fun process(sample: Float): Float {
        val x = sample.toDouble()
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = x
        y2 = y1
        y1 = y
        return y.toFloat()
    }

    companion object {

        // 搁架：BS.1770 的模拟原型，Vb 指数是标准给定的拟合值，不取 Vh 的平方根
        fun highShelf(sampleRate: Int, frequency: Double, gainDb: Double, q: Double): Biquad {
            val k = tan(Math.PI * frequency / sampleRate)
            val vh = 10.0.pow(gainDb / 20.0)
            val vb = vh.pow(0.4996667741545416)
            val a0 = 1.0 + k / q + k * k
            val b0 = (vh + vb * k / q + k * k) / a0
            val b1 = 2.0 * (k * k - vh) / a0
            val b2 = (vh - vb * k / q + k * k) / a0
            val a1 = 2.0 * (k * k - 1.0) / a0
            val a2 = (1.0 - k / q + k * k) / a0
            return Biquad(b0, b1, b2, a1, a2)
        }

        fun highPass(sampleRate: Int, frequency: Double, q: Double): Biquad {
            val k = tan(Math.PI * frequency / sampleRate)
            val a0 = 1.0 + k / q + k * k
            val b0 = 1.0 / a0
            val b1 = -2.0 / a0
            val b2 = 1.0 / a0
            val a1 = 2.0 * (k * k - 1.0) / a0
            val a2 = (1.0 - k / q + k * k) / a0
            return Biquad(b0, b1, b2, a1, a2)
        }
    }
}
