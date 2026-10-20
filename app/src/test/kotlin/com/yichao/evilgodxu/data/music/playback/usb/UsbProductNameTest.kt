package com.yichao.evilgodxu.data.music.playback.usb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 音频栈设备名与 USB 描述符产品名的比对。
 *
 * 两处名称来源不同（ALSA 声卡名 / 描述符 iProduct），面板要用后者去挑前者对应的那台设备，
 * 于是"认错设备"与"认不出设备"都直接表现为能力读错或读不到。
 */
class UsbProductNameTest {

    // 音频栈的 productName 带 ALSA 拼的声卡名前缀，描述符里的 iProduct 不带
    @Test
    fun stripsTheAlsaCardPrefix() {
        assertTrue(
            usbProductNameMatches("USB-Audio - KT02H20 Hifi Audio", "KT02H20 Hifi Audio")
        )
        assertTrue(usbProductNameMatches("KT02H20 Hifi Audio", "USB-Audio - KT02H20 Hifi Audio"))
    }

    // 大小写与首尾空白不构成区别：两处由不同的驱动/固件产出，写法未必一致
    @Test
    fun ignoresCaseAndSurroundingSpaces() {
        assertTrue(usbProductNameMatches("usb-audio -  FiiO K7 ", " fiio k7"))
    }

    // 前缀之外还有差异即判为两台设备：按包含关系匹配会让「K7」与「K7 Pro」互相认错
    @Test
    fun rejectsDifferentDevices() {
        assertFalse(usbProductNameMatches("USB-Audio - FiiO K7", "FiiO K7 Pro"))
        assertFalse(usbProductNameMatches("FiiO K7", "Topping E30"))
    }

    // 任一侧缺失都无从认定，不能因"看起来像"就认下
    @Test
    fun rejectsMissingNames() {
        assertFalse(usbProductNameMatches(null, "FiiO K7"))
        assertFalse(usbProductNameMatches("FiiO K7", null))
        assertFalse(usbProductNameMatches("  ", "FiiO K7"))
    }
}
