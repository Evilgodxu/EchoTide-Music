package com.yichao.evilgodxu.data.music.analysis

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// 频谱分析共用的离散变换与窗函数：平均功率谱（音质与 AI 合成判定）与频谱图（时频分析）
// 走同一份基 2 快速傅里叶变换，避免变换规则出现第二份实现。仅支持 2 的幂长度。
internal object Fft {

    // 窗函数按长度缓存：变换本身可跨线程并发执行，缓存须线程安全
    private val windows = ConcurrentHashMap<Int, FloatArray>()

    // Hann 窗，最末点回零以保证两端连续
    fun hannWindow(size: Int): FloatArray = windows.getOrPut(size) {
        FloatArray(size) { i -> 0.5f - 0.5f * cos(2f * PI.toFloat() * i / (size - 1)) }
    }

    // 就地基 2 快速傅里叶变换：输入实部与虚部，输出未归一化的频谱，长度须为 2 的幂
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

    // 把变换结果的半谱功率累加到目标数组：目标长度取 FFT 长度的一半加一，
    // 覆盖直流到奈奎斯特的全部频率桶，由调用方复用以避免逐帧分配
    fun accumulatePower(re: FloatArray, im: FloatArray, target: FloatArray) {
        for (i in target.indices) {
            target[i] += re[i] * re[i] + im[i] * im[i]
        }
    }
}
