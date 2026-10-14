package com.yichao.evilgodxu.data.music.playback.usb

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 系统音量档位到软件音量的换算。
 *
 * 这条换算直接决定独占期间的实际响度，且错了不会报错、只会"音量键按了不对劲"，故把几个关键点钉住：
 * 满档、静音档、中间档的取整与越界值。
 */
class SystemMediaVolumeTest {

    private val tolerance = 0.0005f

    // 满档必须是正好 100%，归一化后为满幅；低一档都不该是 1.0
    @Test
    fun maxStepIsFullScale() {
        assertEquals(100, volumePercent(15, 15))
        assertEquals(1f, systemVolumeGain(15, 15), tolerance)
    }

    // 静音档必须是真零而不是一个极小值——留残值就等于没静音
    @Test
    fun zeroStepIsSilence() {
        assertEquals(0, volumePercent(0, 15))
        assertEquals(0f, systemVolumeGain(0, 15), tolerance)
    }

    // 中间档四舍五入取整：15 档的第 1 档是 6.67%，报 7% 而不是截断成 6%
    @Test
    fun middleStepRoundsToNearestPercent() {
        assertEquals(7, volumePercent(1, 15))
        assertEquals(0.07f, systemVolumeGain(1, 15), tolerance)
        assertEquals(50, volumePercent(5, 10))
    }

    // 越界档位一律钳到两端：平台读数异常时不能变成负音量或超过满幅
    @Test
    fun outOfRangeStepsClamp() {
        assertEquals(0, volumePercent(-3, 15))
        assertEquals(100, volumePercent(99, 15))
    }

    // 读不到最大档位时按满幅处理：没有基准可摊分，压低音量只会把用户挡在声音之外
    @Test
    fun missingMaxFallsBackToFullScale() {
        assertEquals(100, volumePercent(3, 0))
        assertEquals(1f, systemVolumeGain(3, 0), tolerance)
    }

    // 逐档单调不减：音量键往上是变响、往下是变轻，这条不成立就说明档位被读错了
    @Test
    fun gainIsMonotonicInStep() {
        val gains = (0..15).map { systemVolumeGain(it, 15) }

        assertEquals(gains.sorted(), gains)
    }
}
