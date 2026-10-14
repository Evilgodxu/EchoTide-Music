package com.yichao.evilgodxu.data.music.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 心动模式的准入判定。
 *
 * 判据是两个 USB 输出开关，而不是输出成色：成色要等设备接入、属性与格式被受理之后才成立，
 * 而用户的取向在拨动开关那一刻就定了。判错的表现是「该挡的没挡」（开关已开、设备未接入那段时间里
 * 仍能进入片段播放）或「无端把功能掐掉」。
 */
class ImmersiveOutputTest {

    // 直出开关启用即属沉浸式：与心动模式「把每首裁成 30–45 秒片段」的取向相斥
    @Test
    fun directOutputSwitchCountsAsImmersive() {
        assertTrue(isImmersiveOutput(usbDirectOutputEnabled = true, usbExclusiveEnabled = false))
    }

    // 独占开关同样算：两条路取向一致，差别只在实现
    @Test
    fun exclusiveSwitchCountsAsImmersive() {
        assertTrue(isImmersiveOutput(usbDirectOutputEnabled = false, usbExclusiveEnabled = true))
    }

    // 两个开关互斥，但同时为真时判定不必依赖那一点
    @Test
    fun bothSwitchesCountAsImmersive() {
        assertTrue(isImmersiveOutput(usbDirectOutputEnabled = true, usbExclusiveEnabled = true))
    }

    // 两个开关都关着即照常可进心动模式
    @Test
    fun bothSwitchesOffAllowHighlight() {
        assertFalse(isImmersiveOutput(usbDirectOutputEnabled = false, usbExclusiveEnabled = false))
    }
}
