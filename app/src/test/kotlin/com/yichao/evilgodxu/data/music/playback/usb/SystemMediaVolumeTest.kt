package com.yichao.evilgodxu.data.music.playback.usb

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 系统音量曲线到线性增益的换算。
 *
 * 这条换算直接决定独占期间的实际响度，且错了不会报错、只会"音量键按了不对劲"，故把几个关键点钉住：
 * 满幅、半幅、静音档与越界值。
 */
class SystemMediaVolumeTest {

    private val tolerance = 0.0005f

    // 平台曲线在最大档位给出 0dB，对应不衰减
    @Test
    fun zeroDbIsUnityGain() {
        assertEquals(1f, dbToLinearGain(0f), tolerance)
    }

    // -6.02dB 是幅度减半，也正是"半幅"应有的档位
    @Test
    fun minusSixDbIsHalfAmplitude() {
        assertEquals(0.5f, dbToLinearGain(-6.0206f), tolerance)
    }

    // 静音档在平台上就是负无穷，必须是真零而不是一个极小值——留残值就等于没静音
    @Test
    fun negativeInfinityIsSilence() {
        assertEquals(0f, dbToLinearGain(Float.NEGATIVE_INFINITY), tolerance)
    }

    // 放大没有意义：越过满幅只会削顶，一律按满幅处理
    @Test
    fun amplificationClampsToUnity() {
        assertEquals(1f, dbToLinearGain(6f), tolerance)
        assertEquals(1f, dbToLinearGain(20f), tolerance)
    }

    // 读不到曲线时留给调用方退回线性摊分，本函数不参与该分支；NaN 按"无衰减"处理以免整条流静音
    @Test
    fun notANumberFallsBackToUnity() {
        assertEquals(1f, dbToLinearGain(Float.NaN), tolerance)
    }

    // 逐档单调：音量键往上是变响、往下是变轻，这条不成立就说明曲线被读错了
    @Test
    fun gainIncreasesWithDb() {
        val curve = floatArrayOf(-58f, -44f, -30f, -18f, -8f, 0f)
        val gains = curve.map { dbToLinearGain(it) }

        assertEquals(gains.sorted(), gains)
    }
}
