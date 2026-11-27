package com.yichao.evilgodxu.data.music.analysis

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// 时频分析用的基 2 FFT 与窗函数，仅支持 2 的幂长度
internal object Fft {

    // 窗缓存，可跨线程并发，故须线程安全
    private val windows = ConcurrentHashMap<Int, FloatArray>()

    // 末点回零，两端连续；只构造一次
    fun hannWindow(size: Int): FloatArray = windows.computeIfAbsent(size) {
        FloatArray(size) { i -> 0.5f - 0.5f * cos(2f * PI.toFloat() * i / (size - 1)) }
    }

    // 就地基 2 FFT，未归一化，长度须为 2 的幂
    fun transform(re: FloatArray, im: FloatArray) {
        val n = re.size
        // 位反转重排
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }
        // 蝶形运算
        var len = 2
        while (len <= n) {
            val angle = 2f * PI.toFloat() / len
            val wStepRe = cos(angle)
            val wStepIm = -sin(angle)
            var i = 0
            while (i < n) {
                var wRe = 1f
                var wIm = 0f
                val half = len shr 1
                for (k in 0 until half) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val tRe = re[i + k + half] * wRe - im[i + k + half] * wIm
                    val tIm = re[i + k + half] * wIm + im[i + k + half] * wRe
                    re[i + k] = uRe + tRe
                    im[i + k] = uIm + tIm
                    re[i + k + half] = uRe - tRe
                    im[i + k + half] = uIm - tIm
                    val nextRe = wRe * wStepRe - wIm * wStepIm
                    wIm = wRe * wStepIm + wIm * wStepRe
                    wRe = nextRe
                }
                i += len
            }
            len = len shl 1
        }
    }
}
