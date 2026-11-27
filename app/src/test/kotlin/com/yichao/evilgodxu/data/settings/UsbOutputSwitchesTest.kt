package com.yichao.evilgodxu.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** USB 互斥单向：开一路关另一路，关一路不动另一路 */
class UsbOutputSwitchesTest {

    private val formatLocked = UsbOutputKind.FORMAT_LOCKED
    private val exclusive = UsbOutputKind.EXCLUSIVE

    @Test
    fun `开启直出会关闭独占`() {
        val switches = usbOutputSwitchesAfter(formatLocked, enabled = true)

        assertEquals(true, switches[formatLocked])
        assertEquals(false, switches[exclusive])
    }

    @Test
    fun `开启独占会关闭直出`() {
        val switches = usbOutputSwitchesAfter(exclusive, enabled = true)

        assertEquals(true, switches[exclusive])
        assertEquals(false, switches[formatLocked])
    }

    // 关一路不写出另一路的取值
    @Test
    fun `关闭一路不牵动另一路`() {
        val switches = usbOutputSwitchesAfter(formatLocked, enabled = false)

        assertEquals(false, switches[formatLocked])
        assertNull("关闭直出不应牵动独占", switches[exclusive])
    }

    @Test
    fun `关闭独占不牵动直出`() {
        val switches = usbOutputSwitchesAfter(exclusive, enabled = false)

        assertEquals(false, switches[exclusive])
        assertNull("关闭独占不应牵动直出", switches[formatLocked])
    }

    @Test
    fun `互斥种类互为对方`() {
        assertEquals(exclusive, formatLocked.other())
        assertEquals(formatLocked, exclusive.other())
    }
}
