package com.yichao.evilgodxu.data.music.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 离散变换与窗函数复核。
 *
 * FFT 的结果不另找实现对照就无法发现符号与位反转错误，
 * 故以朴素 DFT 作为基准逐点比对 —— 基准按定义直接求和，不含任何位运算。
 */
class FftTest {

    private companion object {
        const val EPS = 1e-4
    }

    @Test
    fun transformMatchesNaiveDft() {
        val size = 8
        val input = floatArrayOf(1f, 2f, -3f, 4f, 0.5f, -0.25f, 7f, 0f)
        val re = input.copyOf()
        val im = FloatArray(size)
        Fft.transform(re, im)

        val (expectedRe, expectedIm) = naiveDft(input, FloatArray(size))
        for (k in 0 until size) {
            assertEquals("实部 k=$k", expectedRe[k].toDouble(), re[k].toDouble(), EPS)
            assertEquals("虚部 k=$k", expectedIm[k].toDouble(), im[k].toDouble(), EPS)
        }
    }

    @Test
    fun constantSignalHasOnlyDcComponent() {
        val size = 16
        val re = FloatArray(size) { 1f }
        val im = FloatArray(size)
        Fft.transform(re, im)
        assertEquals(size.toDouble(), re[0].toDouble(), EPS)
        for (k in 1 until size) {
            assertEquals("实部 k=$k", 0.0, re[k].toDouble(), EPS)
            assertEquals("虚部 k=$k", 0.0, im[k].toDouble(), EPS)
        }
    }

    @Test
    fun singleImpulseSpreadsUnitMagnitudeAcrossBins() {
        // 时域单个冲激在所有频点上的幅度恒为 1：验证变换未做归一化且不丢能量
        val size = 8
        val re = FloatArray(size).also { it[1] = 1f }
        val im = FloatArray(size)
        Fft.transform(re, im)
        for (k in 0 until size) {
            assertEquals("k=$k", 1.0, (re[k] * re[k] + im[k] * im[k]).toDouble(), EPS)
        }
    }

    @Test
    fun hannWindowIsSymmetricAndZeroAtBothEnds() {
        val window = Fft.hannWindow(64)
        assertEquals(0.0, window[0].toDouble(), EPS)
        // 末点回零以保证两端连续
        assertEquals(0.0, window[63].toDouble(), EPS)
        for (i in 0..31) assertEquals("i=$i", window[i].toDouble(), window[63 - i].toDouble(), EPS)
    }

    @Test
    fun hannWindowIsCachedPerSize() {
        // 逐帧重建窗函数是主要开销之一，缓存后同一长度必须复用同一实例
        assertSame(Fft.hannWindow(512), Fft.hannWindow(512))
    }

    /** 朴素 DFT：按定义逐点求和，作为 FFT 的独立基准 */
    private fun naiveDft(re: FloatArray, im: FloatArray): Pair<FloatArray, FloatArray> {
        val n = re.size
        val outRe = FloatArray(n)
        val outIm = FloatArray(n)
        for (k in 0 until n) {
            var sumRe = 0.0
            var sumIm = 0.0
            for (t in 0 until n) {
                val theta = -2.0 * PI * t * k / n
                sumRe += re[t] * cos(theta) - im[t] * sin(theta)
                sumIm += re[t] * sin(theta) + im[t] * cos(theta)
            }
            outRe[k] = sumRe.toFloat()
            outIm[k] = sumIm.toFloat()
        }
        return outRe to outIm
    }
}
