package com.yichao.evilgodxu.data.music.playback.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UAC 控制请求的编码。
 *
 * 这一层值得单独验证的理由：控制请求打错参数时，设备通常**不报错**，只是把请求忽略掉，表现成
 * "设了采样率却没反应、调了音量却没变化"，从播放侧的日志里看不出是哪一位写错了。所以这里逐个
 * 核对 wValue / wIndex 的位组合与数据字节序，而不是等真机上出问题再回头查。
 */
class UsbAudioRequestsTest {

    // ---------------------------------------------------------------- 请求类型

    // bmRequestType 由「方向 | 类型 | 接收者」三段拼成，逐段核对而非重抄字面量
    @Test
    fun requestTypesCarryDirectionClassAndRecipient() {
        val inBit = 0x80
        val classType = 0x20
        val recipientInterface = 0x01
        val recipientEndpoint = 0x02

        assertEquals(recipientInterface or classType, UsbAudioRequests.OUT_INTERFACE)
        assertEquals(inBit or recipientInterface or classType, UsbAudioRequests.IN_INTERFACE)
        assertEquals(recipientEndpoint or classType, UsbAudioRequests.OUT_ENDPOINT)
        assertEquals(inBit or recipientEndpoint or classType, UsbAudioRequests.IN_ENDPOINT)
    }

    // ---------------------------------------------------------------- wValue / wIndex

    @Test
    fun featureUnitValuePacksControlAndChannel() {
        // 音量选择子 0x02、声道 1 → 0x0201
        assertEquals(0x0201, UsbAudioRequests.featureUnitValue(UsbAudioRequests.CONTROL_VOLUME, 1))
        // 主控的声道号为 0，此时低字节必须确实是 0
        assertEquals(0x0200, UsbAudioRequests.featureUnitValue(UsbAudioRequests.CONTROL_VOLUME, 0))
    }

    @Test
    fun featureUnitIndexPacksUnitAndControlInterface() {
        assertEquals(0x0501, UsbAudioRequests.featureUnitIndex(unitId = 5, controlInterface = 1))
    }

    @Test
    fun clockValueAndIndexPackSelectorAndClock() {
        assertEquals(0x0100, UsbAudioRequests.clockValue(UsbAudioRequests.CONTROL_SAMPLING_FREQ))
        assertEquals(0x2A01, UsbAudioRequests.clockIndex(clockId = 42, controlInterface = 1))
    }

    // 无符号字段的溢出边界：高位必须落在高字节，而不是被静默截断
    @Test
    fun packingKeepsHighBytes() {
        assertEquals(0xFF0A, UsbAudioRequests.featureUnitIndex(unitId = 0xFF, controlInterface = 0x0A))
        assertEquals(0xFF00, UsbAudioRequests.clockValue(0xFF))
    }

    // ---------------------------------------------------------------- 采样率字节序

    @Test
    fun uac1RateIsThreeByteLittleEndian() {
        assertEquals(listOf(0x44, 0xAC, 0x00), UsbAudioRequests.uac1Rate(44100).map { it.toInt() and 0xFF })
        // 176.4kHz 会用到第三个字节，正是 24 位与 16 位的差别所在
        assertEquals(listOf(0x10, 0xB1, 0x02), UsbAudioRequests.uac1Rate(176400).map { it.toInt() and 0xFF })
    }

    @Test
    fun uac2RateIsFourByteLittleEndian() {
        assertEquals(
            listOf(0x80, 0xBB, 0x00, 0x00),
            UsbAudioRequests.uac2Rate(48000).map { it.toInt() and 0xFF },
        )
    }

    @Test
    fun rateRoundTripsThroughItsOwnByteOrder() {
        assertEquals(44100, UsbAudioRequests.unsigned24(UsbAudioRequests.uac1Rate(44100), 0))
    }

    // ---------------------------------------------------------------- 音量值

    // 音量是 1/256 dB 的有符号值，负值必须补码往返，不能按无符号解释
    @Test
    fun volumeValueIsSignedSixteenBit() {
        assertEquals(listOf(0x00, 0xF6), UsbAudioRequests.volumeValue(-2560).map { it.toInt() and 0xFF })
        assertEquals(-2560, UsbAudioRequests.signed16(UsbAudioRequests.volumeValue(-2560), 0))
        assertEquals(0, UsbAudioRequests.signed16(UsbAudioRequests.volumeValue(0), 0))
    }

    // ---------------------------------------------------------------- 音量范围应答

    @Test
    fun volumeRangeTakesTheFirstSubRange() {
        // 子区间数 1，随后 最小 -32768、最大 0、步长 256
        val reply = bytes(0x01, 0x00, 0x00, 0x80, 0x00, 0x00, 0x00, 0x01)

        val range = usbAudioVolumeRange(reply, reply.size)
        assertEquals(-32768, range?.min)
        assertEquals(0, range?.max)
        assertEquals(256, range?.step)
        assertTrue(range!!.usable)
    }

    // 上下限相等表示没有可调范围：不得据此认定音量可设，否则每次设置都会静默失效
    @Test
    fun zeroWidthVolumeRangeIsNotUsable() {
        val reply = bytes(0x01, 0x00, 0x00, 0x80, 0x00, 0x80, 0x00, 0x00)

        val range = usbAudioVolumeRange(reply, reply.size)
        assertEquals(-32768, range?.min)
        assertEquals(-32768, range?.max)
        assertFalse(range!!.usable)
    }

    @Test
    fun volumeRangeRejectsEmptyOrShortReply() {
        val empty = bytes(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        assertNull("没有子区间时应判为读不到", usbAudioVolumeRange(empty, empty.size))

        val short = bytes(0x01, 0x00, 0x00, 0x80)
        assertNull("应答不足一个子区间时应判为读不到", usbAudioVolumeRange(short, short.size))
    }
}

private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
