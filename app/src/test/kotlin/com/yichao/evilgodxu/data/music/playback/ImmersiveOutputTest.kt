package com.yichao.evilgodxu.data.music.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 判据看两个 USB 开关，不看输出成色 */
class ImmersiveOutputTest {

    // 直出启用即沉浸式，与心动模式相斥
    @Test
    fun `直出开关计为沉浸输出`() {
        assertTrue(isImmersiveOutput(usbDirectOutputEnabled = true, usbExclusiveEnabled = false))
    }

    // 独占同样算，只是实现不同
    @Test
    fun `独占开关计为沉浸输出`() {
        assertTrue(isImmersiveOutput(usbDirectOutputEnabled = false, usbExclusiveEnabled = true))
    }

    // 两开关互斥，同时为真也成立
    @Test
    fun `两路开关同时开启仍计为沉浸输出`() {
        assertTrue(isImmersiveOutput(usbDirectOutputEnabled = true, usbExclusiveEnabled = true))
    }

    // 两个开关都关着即照常可进心动模式
    @Test
    fun `两路全关才允许高亮分析`() {
        assertFalse(isImmersiveOutput(usbDirectOutputEnabled = false, usbExclusiveEnabled = false))
    }
}
