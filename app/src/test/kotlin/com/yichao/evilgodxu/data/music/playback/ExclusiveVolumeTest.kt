package com.yichao.evilgodxu.data.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 档位→增益：-80dB 到 0dB，逐级 0.5dB */
class ExclusiveVolumeTest {

    private val tolerance = 0.0005f

    // 满档 0dB，打包器不缩放
    @Test
    fun `最高档为增益 1`() {
        assertEquals(1f, stepToGain(EXCLUSIVE_VOLUME_STEPS), tolerance)
    }

    // 每级 0.5dB，降 20 级为 -10dB
    @Test
    fun `每二十档降低十分贝`() {
        assertEquals(0.316228f, stepToGain(EXCLUSIVE_VOLUME_STEPS - 20), tolerance)
    }

    // 0 档 -80dB 即 1e-4，非零
    @Test
    fun `最低档为负八十分贝`() {
        assertEquals(1e-4f, stepToGain(0), tolerance)
    }

    // 越界取端点，不放大也不为负
    @Test
    fun `越界档位钳到两端`() {
        assertEquals(stepToGain(EXCLUSIVE_VOLUME_STEPS), stepToGain(EXCLUSIVE_VOLUME_STEPS + 5))
        assertEquals(stepToGain(0), stepToGain(-5))
    }

    // 逐级单调，否则级数或衰减写反
    @Test
    fun `增益随档位递增`() {
        val gains = (0..EXCLUSIVE_VOLUME_STEPS).map(::stepToGain)

        assertEquals(gains.sorted(), gains)
    }

    // 默认 -30dB，起播不满幅
    @Test
    fun `默认档为负三十分贝`() {
        val volume = ExclusiveVolume()

        assertEquals(EXCLUSIVE_VOLUME_DEFAULT_STEP, volume.currentStep)
        assertEquals(0.031623f, volume.gain(), tolerance)
    }

    // 写入带钳制，如实回答是否真的变了
    @Test
    fun `设置档位会钳制并回报变化`() {
        val volume = ExclusiveVolume()

        assertTrue(volume.setStep(EXCLUSIVE_VOLUME_DEFAULT_STEP + 1))
        assertFalse("同值写入不算变化", volume.setStep(EXCLUSIVE_VOLUME_DEFAULT_STEP + 1))
        assertTrue(volume.setStep(EXCLUSIVE_VOLUME_STEPS + 10))
        assertEquals(EXCLUSIVE_VOLUME_STEPS, volume.currentStep)
        volume.setStep(-10)
        assertEquals(0, volume.currentStep)
    }

    // 静音只归零增益，档位保留
    @Test
    fun `静音不丢失当前档位`() {
        val volume = ExclusiveVolume()
        val step = volume.currentStep

        assertTrue(volume.setMuted(true))
        assertEquals(0f, volume.gain(), tolerance)
        assertEquals(step, volume.currentStep)
        volume.setMuted(false)
        assertEquals(stepToGain(step), volume.gain(), tolerance)
    }
}
