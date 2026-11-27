package com.yichao.evilgodxu.data.music.analysis

import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** K 加权 + 两级门限的整体响度 */
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
        // -9.72 + K 加权 0.65dB 得 -9.07
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

        // -18 目标，-9.07 增益 -8.93
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
        // 峰值取原始样本，不取滤波后
        val meter = LoudnessMeter(sampleRate = 48_000, channels = 1)
        feed(meter, frequency = 60.0, amplitude = 0.8, seconds = 2.0, sampleRate = 48_000)
        val result = meter.result()!!

        assertTrue(result.peak > 0.79 && abs(result.peak - 0.8) < 0.01)
    }
}
