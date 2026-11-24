package com.yichao.evilgodxu.data.music.analysis

import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 响度计：K 加权 + 两级门限的整体响度统计。
 *
 * 用合成波形验证口径而不是比对某个文件的结果：满幅正弦在 1 kHz 处的响度可以手算，
 * 系数推导错一位、门限用总和而非均值、偏移常数写反，都会立刻偏离这个值。
 */
class LoudnessMeterTest {

    private fun feed(meter: LoudnessMeter, frequency: Double, amplitude: Double, seconds: Double, sampleRate: Int) {
        val totalFrames = (sampleRate * seconds).toInt()
        val block = 1024
        val buffer = FloatArray(block)
        var written = 0
        while (written < totalFrames) {
            val frames = minOf(block, totalFrames - written)
            for (i in 0 until frames) {
                buffer[i] = (amplitude * sin(2.0 * Math.PI * frequency * (written + i) / sampleRate)).toFloat()
            }
            meter.accept(buffer, frames)
            written += frames
        }
    }

    @Test
    fun `满幅一半的千赫兹正弦响度约负九点零七LUFS`() {
        // 幅度 0.5 的正弦均方为 0.125 → -0.691 + 10·log10(0.125) = -9.72，
        // 再叠加 K 加权在 1 kHz 处的 +0.65 dB（BS.1770 系数），得 -9.07 LUFS
        val meter = LoudnessMeter(sampleRate = 48_000, channels = 1)
        feed(meter, frequency = 1_000.0, amplitude = 0.5, seconds = 3.0, sampleRate = 48_000)
        val result = meter.result()!!

        assertEquals(-9.07, result.loudnessLufs, 0.1)
        assertEquals(0.5, result.peak, 0.001)
    }

    @Test
    fun `增益按十八LUFS目标换算`() {
        val meter = LoudnessMeter(sampleRate = 48_000, channels = 1)
        feed(meter, frequency = 1_000.0, amplitude = 0.5, seconds = 3.0, sampleRate = 48_000)
        val result = meter.result()!!

        // 目标 -18 LUFS，测到 -9.07 时增益约 -8.93 dB
        assertEquals(-8.93, result.gainDb, 0.1)
        assertEquals(-18.0, result.loudnessLufs + result.gainDb, 0.001)
    }

    @Test
    fun `静音不产生结果`() {
        val meter = LoudnessMeter(sampleRate = 44_100, channels = 2)
        meter.accept(FloatArray(44_100 * 2), 44_100)
        assertNull(meter.result())
    }

    @Test
    fun `样本不足一块时不产生结果`() {
        val meter = LoudnessMeter(sampleRate = 48_000, channels = 1)
        feed(meter, frequency = 1_000.0, amplitude = 0.9, seconds = 0.2, sampleRate = 48_000)
        assertNull(meter.result())
    }

    @Test
    fun `双声道同幅度的响度与单声道一致`() {
        val mono = LoudnessMeter(sampleRate = 44_100, channels = 1)
        feed(mono, frequency = 1_000.0, amplitude = 0.4, seconds = 2.0, sampleRate = 44_100)

        val stereo = LoudnessMeter(sampleRate = 44_100, channels = 2)
        val totalFrames = 44_100 * 2
        val block = 512
        val buffer = FloatArray(block * 2)
        var written = 0
        while (written < totalFrames) {
            val frames = minOf(block, totalFrames - written)
            for (i in 0 until frames) {
                val value = (0.4 * sin(2.0 * Math.PI * 1_000.0 * (written + i) / 44_100)).toFloat()
                buffer[i * 2] = value
                buffer[i * 2 + 1] = value
            }
            stereo.accept(buffer, frames)
            written += frames
        }

        assertEquals(mono.result()!!.loudnessLufs, stereo.result()!!.loudnessLufs, 0.01)
    }

    @Test
    fun `峰值取原始样本而非滤波后样本`() {
        // 低频信号经过高通会被衰减，峰值仍应取自原始样本
        val meter = LoudnessMeter(sampleRate = 48_000, channels = 1)
        feed(meter, frequency = 60.0, amplitude = 0.8, seconds = 2.0, sampleRate = 48_000)
        val result = meter.result()!!

        assertTrue(result.peak > 0.79 && abs(result.peak - 0.8) < 0.01)
    }
}
