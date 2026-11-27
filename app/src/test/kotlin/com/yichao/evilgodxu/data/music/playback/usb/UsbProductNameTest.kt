package com.yichao.evilgodxu.data.music.playback.usb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 用描述符产品名匹配 ALSA 声卡名 */
class UsbProductNameTest {

    // ALSA 名带前缀，iProduct 不带
    @Test
    fun `剥离 ALSA 卡名前缀`() {
        assertTrue(
            usbProductNameMatches("USB-Audio - KT02H20 Hifi Audio", "KT02H20 Hifi Audio")
        )
        assertTrue(usbProductNameMatches("KT02H20 Hifi Audio", "USB-Audio - KT02H20 Hifi Audio"))
    }

    // 大小写与首尾空白不构成区别
    @Test
    fun `忽略大小写与首尾空格`() {
        assertTrue(usbProductNameMatches("usb-audio -  FiiO K7 ", " fiio k7"))
    }

    // 前缀之外有差异即两台设备
    @Test
    fun `不同设备被拒绝`() {
        assertFalse(usbProductNameMatches("USB-Audio - FiiO K7", "FiiO K7 Pro"))
        assertFalse(usbProductNameMatches("FiiO K7", "Topping E30"))
    }

    // 任一侧缺失即无从认定
    @Test
    fun `缺失名称被拒绝`() {
        assertFalse(usbProductNameMatches(null, "FiiO K7"))
        assertFalse(usbProductNameMatches("FiiO K7", null))
        assertFalse(usbProductNameMatches("  ", "FiiO K7"))
    }
}
