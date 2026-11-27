package com.yichao.evilgodxu.data.music.playback.usb

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 位深取值直接决定设备筛选 */
class UsbExclusiveAudioSinkTest {

    @Test
    fun `整型编码一一对应`() {
        assertEquals(8, sourceBitDepthOf(C.ENCODING_PCM_8BIT))
        assertEquals(16, sourceBitDepthOf(C.ENCODING_PCM_16BIT))
        assertEquals(24, sourceBitDepthOf(C.ENCODING_PCM_24BIT))
        assertEquals(32, sourceBitDepthOf(C.ENCODING_PCM_32BIT))
    }

    // 浮点按 24 位，取 32 或 16 都错
    @Test
    fun `浮点按 24 位计`() {
        assertEquals(24, sourceBitDepthOf(C.ENCODING_PCM_FLOAT))
    }

    // 非线性编码判 0，退回平台路径
    @Test
    fun `非线性编码没有位深`() {
        assertEquals(0, sourceBitDepthOf(C.ENCODING_INVALID))
        assertEquals(0, sourceBitDepthOf(C.ENCODING_AAC_LC))
    }

    // 只算环内，已取走不计
    @Test
    fun `驻留只计仍在环形缓冲中的部分`() {
        assertEquals(3528, ringResidentFrames(writtenFrames = 3528, sentFrames = 0, bufferFrames = 3528))
        assertEquals(705, ringResidentFrames(writtenFrames = 4233, sentFrames = 3528, bufferFrames = 3528))
        assertEquals(0, ringResidentFrames(writtenFrames = 3528, sentFrames = 3528, bufferFrames = 3528))
    }

    // 快照滞后时按容量封顶
    @Test
    fun `驻留量以容量为上限`() {
        assertEquals(3528, ringResidentFrames(writtenFrames = 5000, sentFrames = 1000, bufferFrames = 3528))
    }

    // 负值压成 0，不报给界面
    @Test
    fun `驻留量不会为负`() {
        assertEquals(0, ringResidentFrames(writtenFrames = 100, sentFrames = 400, bufferFrames = 3528))
    }

    // 5ms@44.1k 立体声 220 帧 440 样本
    @Test
    fun `渐入样本数按采样率与声道折算`() {
        assertEquals(440, fadeInSampleCount(44100, 2))
        assertEquals(220, fadeInSampleCount(44100, 1))
        assertEquals(480, fadeInSampleCount(48000, 2))
    }

    // 参数不成立则不渐入，宁可硬起
    @Test
    fun `采样率或声道无效时不渐入`() {
        assertEquals(0, fadeInSampleCount(0, 2))
        assertEquals(0, fadeInSampleCount(44100, 0))
    }

    // 起点静音，走满取目标，越界不回
    @Test
    fun `渐入包络起于静音而终于目标增益`() {
        val total = fadeInSampleCount(44100, 2)

        assertEquals(0, fadeGainQ15(consumedSamples = 0, totalSamples = total, targetQ15 = UNITY_GAIN_Q15))
        assertEquals(
            UNITY_GAIN_Q15,
            fadeGainQ15(consumedSamples = total, totalSamples = total, targetQ15 = UNITY_GAIN_Q15),
        )
        assertEquals(
            UNITY_GAIN_Q15,
            fadeGainQ15(consumedSamples = total * 2, totalSamples = total, targetQ15 = UNITY_GAIN_Q15),
        )
    }

    // 单调不减，回头会听出抖动
    @Test
    fun `渐入包络单调不减`() {
        val total = 200
        val gains = (0..total).map { fadeGainQ15(it, total, UNITY_GAIN_Q15) }

        assertEquals(gains.sorted(), gains)
        assertTrue(gains.first() == 0 && gains.last() == UNITY_GAIN_Q15)
    }

    // 无渐入时直接取目标增益
    @Test
    fun `渐入长度为零时直接用目标增益`() {
        assertEquals(UNITY_GAIN_Q15, fadeGainQ15(consumedSamples = 0, totalSamples = 0, targetQ15 = UNITY_GAIN_Q15))
    }
}
