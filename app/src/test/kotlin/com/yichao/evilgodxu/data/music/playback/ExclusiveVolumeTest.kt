package com.yichao.evilgodxu.data.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 独占音量的档位换算与状态。
 *
 * 这条换算直接决定独占期间的实际响度，算错不会报错、只会"音量键按了不对劲"，故把两端、每级衰减、
 * 越界、默认档位与静音行为钉住。
 */
class ExclusiveVolumeTest {

    private val tolerance = 0.0005f

    // 满档即满幅 0 dB：打包器整段缩放不参与，写出仍是逐位原样
    @Test
    fun topStepIsUnityGain() {
        assertEquals(1f, stepToGain(EXCLUSIVE_VOLUME_STEPS), tolerance)
    }

    // 每级 0.5 dB：降 20 级恰是 -10 dB，幅度为 10^(-0.5)
    @Test
    fun everyTwentyStepsIsAMinusTenDecibelDrop() {
        assertEquals(0.316228f, stepToGain(EXCLUSIVE_VOLUME_STEPS - 20), tolerance)
    }

    // 0 档是量程下界 -80 dB：1e-4 的幅度，听不见但不是零
    @Test
    fun bottomStepIsMinusEightyDecibels() {
        assertEquals(1e-4f, stepToGain(0), tolerance)
    }

    // 越界一律按两端取值：档位来自系统音量面板与按键，不该有机会变成放大或负音量
    @Test
    fun outOfRangeStepsClampToEnds() {
        assertEquals(stepToGain(EXCLUSIVE_VOLUME_STEPS), stepToGain(EXCLUSIVE_VOLUME_STEPS + 5))
        assertEquals(stepToGain(0), stepToGain(-5))
    }

    // 逐级单调：音量键往上是变响、往下是变轻，这条不成立就说明级数或衰减写反了
    @Test
    fun gainIncreasesWithStep() {
        val gains = (0..EXCLUSIVE_VOLUME_STEPS).map(::stepToGain)

        assertEquals(gains.sorted(), gains)
    }

    // 默认档位是 -30 dB：起播不会一上来就是满幅
    @Test
    fun defaultStepIsMinusThirtyDecibels() {
        val volume = ExclusiveVolume()

        assertEquals(EXCLUSIVE_VOLUME_DEFAULT_STEP, volume.currentStep)
        assertEquals(0.031623f, volume.gain(), tolerance)
    }

    // 档位写入带钳制，并如实回答"是否真的变了"——调用方据此决定落盘与会话播报，两者都不该被
    // 无效写入牵着走
    @Test
    fun setStepClampsAndReportsChange() {
        val volume = ExclusiveVolume()

        assertTrue(volume.setStep(EXCLUSIVE_VOLUME_DEFAULT_STEP + 1))
        assertFalse("同值写入不算变化", volume.setStep(EXCLUSIVE_VOLUME_DEFAULT_STEP + 1))
        assertTrue(volume.setStep(EXCLUSIVE_VOLUME_STEPS + 10))
        assertEquals(EXCLUSIVE_VOLUME_STEPS, volume.currentStep)
        volume.setStep(-10)
        assertEquals(0, volume.currentStep)
    }

    // 静音只是增益归零，档位留在原处：取消静音即回到原响度，不必让用户重新找一遍
    @Test
    fun muteSilencesWithoutLosingTheStep() {
        val volume = ExclusiveVolume()
        val step = volume.currentStep

        assertTrue(volume.setMuted(true))
        assertEquals(0f, volume.gain(), tolerance)
        assertEquals(step, volume.currentStep)
        volume.setMuted(false)
        assertEquals(stepToGain(step), volume.gain(), tolerance)
    }
}
