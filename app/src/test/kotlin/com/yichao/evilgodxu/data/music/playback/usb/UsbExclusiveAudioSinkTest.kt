package com.yichao.evilgodxu.data.music.playback.usb

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解码编码到源位深的换算。
 *
 * 这个取值直接决定设备筛选的结果，判宽了会让低位被静默丢掉（听感上未必立刻可辨），
 * 判窄了会把设备无谓地挡在门外，故各档取值逐个钉住。
 */
class UsbExclusiveAudioSinkTest {

    @Test
    fun `整型编码一一对应`() {
        assertEquals(8, sourceBitDepthOf(C.ENCODING_PCM_8BIT))
        assertEquals(16, sourceBitDepthOf(C.ENCODING_PCM_16BIT))
        assertEquals(24, sourceBitDepthOf(C.ENCODING_PCM_24BIT))
        assertEquals(32, sourceBitDepthOf(C.ENCODING_PCM_32BIT))
    }

    // 浮点按 24 位计：取 32 会把大量 24 位解码器挡在门外，取 16 则会放过会丢低位的搭配
    @Test
    fun `浮点按 24 位计`() {
        assertEquals(24, sourceBitDepthOf(C.ENCODING_PCM_FLOAT))
    }

    // 非线性编码无从判断位深：判 0 会让格式规划直接不成立，退回平台路径
    @Test
    fun `非线性编码没有位深`() {
        assertEquals(0, sourceBitDepthOf(C.ENCODING_INVALID))
        assertEquals(0, sourceBitDepthOf(C.ENCODING_AAC_LC))
    }

    // 驻留只算还在环里的那一段：已取走（装入 URB）的量不计，否则面板上它会超过"当前缓冲"这一行
    @Test
    fun `驻留只计仍在环形缓冲中的部分`() {
        assertEquals(3528, ringResidentFrames(writtenFrames = 3528, sentFrames = 0, bufferFrames = 3528))
        assertEquals(705, ringResidentFrames(writtenFrames = 4233, sentFrames = 3528, bufferFrames = 3528))
        assertEquals(0, ringResidentFrames(writtenFrames = 3528, sentFrames = 3528, bufferFrames = 3528))
    }

    // 已取走的量取自播放线程留下的快照，落后于已写出量时按容量封顶——缓冲容不下更多，超出只可能是这份滞后
    @Test
    fun `驻留量以容量为上限`() {
        assertEquals(3528, ringResidentFrames(writtenFrames = 5000, sentFrames = 1000, bufferFrames = 3528))
    }

    // 负值只可能来自快照与记账错位，压成 0 而不是把负驻留报给界面
    @Test
    fun `驻留量不会为负`() {
        assertEquals(0, ringResidentFrames(writtenFrames = 100, sentFrames = 400, bufferFrames = 3528))
    }

    // 渐入长度按帧数与声道数折算：5ms 在 44.1k 立体声下是 220 帧、440 个样本，单声道 220 个
    @Test
    fun `渐入样本数按采样率与声道折算`() {
        assertEquals(440, fadeInSampleCount(44100, 2))
        assertEquals(220, fadeInSampleCount(44100, 1))
        assertEquals(480, fadeInSampleCount(48000, 2))
    }

    // 参数不成立就不渐入：宁可硬起，也不拿一个除零或负数去算包络
    @Test
    fun `采样率或声道无效时不渐入`() {
        assertEquals(0, fadeInSampleCount(0, 2))
        assertEquals(0, fadeInSampleCount(44100, 0))
    }

    // 包络的两端：起点是静音，走满即目标增益。越过终点仍取目标值，跨块时才不会回头
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

    // 单调不减：中途回头会听成一次抖动，而这正是要消掉的东西
    @Test
    fun `渐入包络单调不减`() {
        val total = 200
        val gains = (0..total).map { fadeGainQ15(it, total, UNITY_GAIN_Q15) }

        assertEquals(gains.sorted(), gains)
        assertTrue(gains.first() == 0 && gains.last() == UNITY_GAIN_Q15)
    }

    // 无渐入时取目标增益：调用方据此可以直接以当前音量硬起
    @Test
    fun `渐入长度为零时直接用目标增益`() {
        assertEquals(UNITY_GAIN_Q15, fadeGainQ15(consumedSamples = 0, totalSamples = 0, targetQ15 = UNITY_GAIN_Q15))
    }
}
