package com.yichao.evilgodxu.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 两路 USB 输出的开关取值。
 *
 * 互斥是**单向**的：开启一路必然关闭另一路，因为 USB 音频接口同一时刻只能有一个持有者；但关闭一路
 * 不牵动另一路。用例把这两个方向分开验证——只测"开 A 关 B"会漏掉"关 A 时误关 B"，而后者恰恰是用户
 * 最会遇到的路径：把某一路关掉时，正在用的另一路被一起关掉。
 */
class UsbOutputSwitchesTest {

    private val formatLocked = UsbOutputKind.FORMAT_LOCKED
    private val exclusive = UsbOutputKind.EXCLUSIVE

    @Test
    fun turningOnDirectOutputClearsExclusive() {
        val switches = usbOutputSwitchesAfter(formatLocked, enabled = true)

        assertEquals(true, switches[formatLocked])
        assertEquals(false, switches[exclusive])
    }

    @Test
    fun turningOnExclusiveClearsDirectOutput() {
        val switches = usbOutputSwitchesAfter(exclusive, enabled = true)

        assertEquals(true, switches[exclusive])
        assertEquals(false, switches[formatLocked])
    }

    // 关闭一路不得写出另一路的取值：另一路此时可能正开着
    @Test
    fun turningOffDoesNotTouchTheOtherSwitch() {
        val switches = usbOutputSwitchesAfter(formatLocked, enabled = false)

        assertEquals(false, switches[formatLocked])
        assertNull("关闭直出不应牵动独占", switches[exclusive])
    }

    @Test
    fun turningOffExclusiveDoesNotTouchDirectOutput() {
        val switches = usbOutputSwitchesAfter(exclusive, enabled = false)

        assertEquals(false, switches[exclusive])
        assertNull("关闭独占不应牵动直出", switches[formatLocked])
    }

    @Test
    fun otherSwapsTheTwoKinds() {
        assertEquals(exclusive, formatLocked.other())
        assertEquals(formatLocked, exclusive.other())
    }
}
