package com.yichao.evilgodxu.data.music.analysis

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.tan

/** 响度、增益与线性峰值 */
internal data class ReplayGainMeasurement(
    val loudnessLufs: Double,
    val gainDb: Double,
    val peak: Double,
)

/**
 * 流式响度计：K 加权 + 两级门限。
 * 400ms 一块、每 100ms 一块。
 * 先 -70 剔静音，再 -10 LU 剔偏弱。
 */
internal class LoudnessMeter(sampleRate: Int, channels: Int) {

    private val channelCount = channels.coerceAtLeast(1)
    private val blockFrames = (sampleRate * BLOCK_MS / 1_000).coerceAtLeast(1)
    private val stepFrames = (sampleRate * STEP_MS / 1_000).coerceAtLeast(1)
    private val filters = Array(channelCount) { KWeighting(sampleRate) }
    // 逐声道滑动平方和，写新样本时减去被覆盖的旧样本
    private val sliding = Array(channelCount) { FloatArray(blockFrames) }
    private var writeIndex = 0
    private var framesSinceStep = 0
    private var framesSeen = 0L
    private var squareSum = 0.0
    private var peak = 0.0
    private val blockLoudnessSquares = ArrayList<Double>()

    /** 消费交错浮点样本，frames 不含声道 */
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
            // 窗口未满不计，免得半块与整块并列
            if (framesSeen < blockFrames) continue
            framesSinceStep++
            if (framesSinceStep < stepFrames) continue
            framesSinceStep = 0
            blockLoudnessSquares.add(squareSum / (blockFrames.toDouble() * channelCount))
        }
    }

    /** 门限加权后的整体响度，无块时 null */
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
        // 相对门限：均值 -10 LU，即乘 0.1
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
        // 响度公式常数，满幅正弦的偏移
        const val LOUDNESS_OFFSET = 0.691
        const val ABSOLUTE_GATE_LUFS = -70.0
        const val RELATIVE_GATE_LU = 10.0
    }
}

/**
 * ReplayGain 2.0 目标响度 -18 LUFS。
 * 增益 = 该值 - 实测响度。
 */
internal const val REPLAY_GAIN_REFERENCE_LUFS = -18.0

/**
 * K 加权：高频搁架 + 高通两级双二阶。
 * 系数按模拟原型推导，不用 RBJ，否则差约 0.2 dB。
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

/** 直接 I 型双二阶，系数按 a0 归一 */
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

        // 搁架用模拟原型，Vb 取拟合值
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
