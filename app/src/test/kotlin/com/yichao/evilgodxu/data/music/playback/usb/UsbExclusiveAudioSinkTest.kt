package com.yichao.evilgodxu.data.music.playback.usb

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 解码编码到源位深的换算。
 *
 * 这个取值直接决定设备筛选的结果，判宽了会让低位被静默丢掉（听感上未必立刻可辨），
 * 判窄了会把设备无谓地挡在门外，故各档取值逐个钉住。
 */
class UsbExclusiveAudioSinkTest {

    @Test
    fun integerEncodingsMapOneToOne() {
        assertEquals(8, sourceBitDepthOf(C.ENCODING_PCM_8BIT))
        assertEquals(16, sourceBitDepthOf(C.ENCODING_PCM_16BIT))
        assertEquals(24, sourceBitDepthOf(C.ENCODING_PCM_24BIT))
        assertEquals(32, sourceBitDepthOf(C.ENCODING_PCM_32BIT))
    }

    // 浮点按 24 位计：取 32 会把大量 24 位解码器挡在门外，取 16 则会放过会丢低位的搭配
    @Test
    fun floatCountsAsTwentyFourBits() {
        assertEquals(24, sourceBitDepthOf(C.ENCODING_PCM_FLOAT))
    }

    // 非线性编码无从判断位深：判 0 会让格式规划直接不成立，退回平台路径
    @Test
    fun nonLinearEncodingHasNoBitDepth() {
        assertEquals(0, sourceBitDepthOf(C.ENCODING_INVALID))
        assertEquals(0, sourceBitDepthOf(C.ENCODING_AAC_LC))
    }
}
