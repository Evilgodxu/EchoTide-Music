package com.yichao.evilgodxu.data.music.playback.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** UAC1/UAC2 描述符解析；四处易读错 */
class UsbAudioDescriptorsTest {

    // UAC1

    @Test
    fun `UAC1 离散采样率连同音量与反馈一并解析`() {
        val device = UsbAudioDescriptorParser.parse(uac1DiscreteRates())

        assertEquals(1, device.uacVersion)
        assertEquals(1, device.controlInterfaceNumber)
        assertEquals(1, device.outputs.size)

        val format = device.outputs.single()
        assertEquals(2, format.interfaceNumber)
        // alt 0 零带宽，不可播放
        assertEquals(1, format.altSetting)
        assertEquals(1, format.endpointAddress)
        assertEquals(192, format.maxPacketBytes)
        assertEquals(1, format.interval)
        assertEquals(UsbAudioSyncType.ASYNCHRONOUS, format.syncType)
        assertEquals(0x82, format.feedbackEndpoint)
        assertEquals(4, format.feedbackMaxPacketBytes)
        assertEquals(1, format.formatTag)
        assertEquals(2, format.channels)
        assertEquals(3, format.subslotBytes)
        assertEquals(24, format.bitResolution)
        assertEquals(listOf(44100, 48000), format.rates)
        assertFalse(format.continuousRates)
        // 采样率控制声明在类特定端点
        assertTrue(format.rateControlOnEndpoint)
        assertEquals(1, format.terminalLink)
        assertEquals(0, format.clockId)
        assertEquals(6, format.frameBytes)
    }

    @Test
    fun `沿终端链路找到 UAC1 功能单元`() {
        val device = UsbAudioDescriptorParser.parse(uac1DiscreteRates())

        val feature = device.playbackFeatureUnit(device.outputs.single())
        assertEquals(2, feature?.unitId)
        assertTrue(feature!!.masterMute)
        assertTrue(feature.masterVolume)
        assertEquals(listOf(true, true), feature.channelVolume)
    }

    // 候选只按声明挑，不试错建轨
    @Test
    fun `UAC1 未声明的采样率不支持`() {
        val format = UsbAudioDescriptorParser.parse(uac1DiscreteRates()).outputs.single()

        assertTrue(format.supportsRate(44100))
        assertTrue(format.supportsRate(48000))
        assertFalse(format.supportsRate(96000))
        assertFalse(format.supportsRate(0))
    }

    @Test
    fun `UAC1 连续范围保持声明的边界`() {
        val format = UsbAudioDescriptorParser.parse(uac1ContinuousRange()).outputs.single()

        assertTrue(format.continuousRates)
        assertEquals(44100, format.minRate)
        assertEquals(96000, format.maxRate)
        assertTrue(format.supportsRate(88200))
        assertFalse(format.supportsRate(192000))
    }

    // UAC2

    @Test
    fun `UAC2 采样率取自经终端到达的时钟`() {
        val device = UsbAudioDescriptorParser.parse(uac2Async())
        val format = device.outputs.single()

        assertEquals(2, device.uacVersion)
        // 终端声明时钟 42，接源 41
        assertEquals(42, format.clockId)
        assertEquals(41, device.resolveClockSource(format.clockId) { 0 })
        assertTrue(device.clock(41)!!.frequencyWritable)
        assertTrue(device.clock(42)!!.selectorWritable)
    }

    @Test
    fun `UAC2 格式携带子槽深度与反馈`() {
        val format = UsbAudioDescriptorParser.parse(uac2Async()).outputs.single()

        assertEquals(1, format.formatTag)
        assertEquals(2, format.channels)
        assertEquals(4, format.subslotBytes)
        assertEquals(32, format.bitResolution)
        assertEquals(8, format.frameBytes)
        assertEquals(UsbAudioSyncType.ASYNCHRONOUS, format.syncType)
        assertEquals(0x81, format.feedbackEndpoint)
        // 档位运行期应答，描述符为空正常
        assertTrue(format.rates.isEmpty())
        assertFalse(format.rateControlOnEndpoint)
    }

    @Test
    fun `UAC2 可写音量被认领`() {
        val device = UsbAudioDescriptorParser.parse(uac2Async(volumeControls = 0x0F))

        val feature = device.playbackFeatureUnit(device.outputs.single())
        assertEquals(2, feature?.unitId)
        assertTrue(feature!!.masterVolume)
        assertTrue(feature.masterMute)
        assertEquals(listOf(true, true), feature.channelVolume)
    }

    // 0b10 不可写，不得当作可用控制
    @Test
    fun `UAC2 只读音量不产出可用控制`() {
        val device = UsbAudioDescriptorParser.parse(uac2Async(volumeControls = 0x02))

        assertNull(device.playbackFeatureUnit(device.outputs.single()))
    }

    // 边界

    // 遇未建模单元即停止回溯
    @Test
    fun `未知单元之后的功能单元不被认领`() {
        val device = UsbAudioDescriptorParser.parse(uac1DiscreteRates(featureFedByMixer = true))

        assertNull(device.playbackFeatureUnit(device.outputs.single()))
    }

    @Test
    fun `只解析第一份配置`() {
        val raw = uac1DiscreteRates() +
            descriptor(0x09, 0x02, 0x00, 0x00, 0x01, 0x01, 0x00, 0x80, 0x32)

        assertEquals(1, UsbAudioDescriptorParser.parse(raw).outputs.size)
    }

    // 长度不合法即停止推进
    @Test
    fun `长度异常时停止而不越界读取`() {
        val truncated = descriptor(0x12, 0x01) + descriptor(0x00, 0x04)
        val device = UsbAudioDescriptorParser.parse(truncated)

        assertEquals(0, device.uacVersion)
        assertEquals(-1, device.controlInterfaceNumber)
        assertTrue(device.outputs.isEmpty())
    }

    @Test
    fun `缺格式描述符时不产出可播放输出`() {
        val device = UsbAudioDescriptorParser.parse(uac1WithoutFormatDescriptor())

        assertEquals(1, device.uacVersion)
        assertTrue(device.outputs.isEmpty())
    }

    // 速率范围展开

    @Test
    fun `范围只展开为常用音频采样率`() {
        // 单区间 44100..96000，步长 0
        val data = descriptor(
            0x01, 0x00,
            0x44, 0xAC, 0x00, 0x00,
            0x00, 0x77, 0x01, 0x00,
            0x00, 0x00, 0x00, 0x00,
        )

        assertEquals(listOf(44100, 48000, 88200, 96000), usbAudioRatesFromRange(data, 0, data.size))
    }

    @Test
    fun `零宽范围保留其单一采样率`() {
        val data = descriptor(
            0x01, 0x00,
            0x80, 0xBB, 0x00, 0x00,
            0x80, 0xBB, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
        )

        assertEquals(listOf(48000), usbAudioRatesFromRange(data, 0, data.size))
    }

    @Test
    fun `超出音频频段的范围不产出内容`() {
        val data = descriptor(
            0x01, 0x00,
            0x10, 0x27, 0x00, 0x00,
            0x20, 0x4E, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
        )

        assertTrue(usbAudioRatesFromRange(data, 0, data.size).isEmpty())
    }

    @Test
    fun `截断的范围应答被忽略`() {
        val declared = descriptor(0x02, 0x00, 0x44, 0xAC, 0x00, 0x00)

        assertTrue(usbAudioRatesFromRange(declared, 0, declared.size).isEmpty())
    }

    /** 档位表：区间下界以下不得混入 */
    @Test
    fun `连续范围与范围应答共用同一张采样率表`() {
        assertEquals(
            listOf(48000, 88200, 96000, 176400, 192000, 352800, 384000),
            usbAudioRatesInRange(48000, 384000, 0),
        )
    }

    @Test
    fun `连续范围遵循自身步长`() {
        // 步长自下界起算，只取格点档位
        assertEquals(listOf(44100, 88200), usbAudioRatesInRange(44100, 96000, 44100))
    }

    @Test
    fun `倒置范围不产出内容`() {
        assertTrue(usbAudioRatesInRange(96000, 48000, 0).isEmpty())
    }

    // 合成描述符

    /** UAC1 播放设备；可插入选择器 */
    private fun uac1DiscreteRates(featureFedByMixer: Boolean = false): ByteArray {
        val featureSource = if (featureFedByMixer) 0x05 else 0x01
        return descriptors(
            deviceDescriptor(),
            configurationDescriptor(interfaces = 2),
            interfaceDescriptor(number = 1, altSetting = 0, endpoints = 0, subclass = 1, protocol = 0),
            // AC 头实物必有，留着贴近真机
            descriptor(0x09, 0x24, 0x01, 0x00, 0x01, 0x0A, 0x00, 0x01, 0x02),
            descriptor(0x0C, 0x24, 0x02, 0x01, 0x01, 0x01, 0x00, 0x02, 0x03, 0x00, 0x00, 0x00),
            if (featureFedByMixer) {
                // 选择器单元 5，由输入终端 1 馈入
                descriptor(0x08, 0x24, 0x05, 0x05, 0x01, 0x01, 0x00, 0x00)
            } else {
                ByteArray(0)
            },
            // Feature Unit 2：主控 0x03，声道 0x02
            // 长度 7+宽度×条目，末尾 iFeature
            descriptor(0x0A, 0x24, 0x06, 0x02, featureSource, 0x01, 0x03, 0x02, 0x02, 0x00),
            // 输出终端 3 由 FU2 馈入
            descriptor(0x09, 0x24, 0x03, 0x03, 0x01, 0x03, 0x00, 0x02, 0x00),
            interfaceDescriptor(number = 2, altSetting = 0, endpoints = 0, subclass = 2, protocol = 0),
            interfaceDescriptor(number = 2, altSetting = 1, endpoints = 1, subclass = 2, protocol = 0),
            descriptor(0x07, 0x24, 0x01, 0x01, 0x00, 0x01, 0x00),
            // Type I：2 声道，3 字节子槽
            descriptor(
                0x0E, 0x24, 0x02, 0x01, 0x02, 0x03, 0x18, 0x02,
                0x44, 0xAC, 0x00, 0x80, 0xBB, 0x00,
            ),
            // 等时 OUT 异步，指向反馈端点
            descriptor(0x09, 0x05, 0x01, 0x05, 0xC0, 0x00, 0x01, 0x00, 0x82),
            // bmAttributes 位 0 表采样率可设
            descriptor(0x07, 0x25, 0x01, 0x01, 0x00, 0x00, 0x00),
            // 等时 IN、用途为反馈
            descriptor(0x07, 0x05, 0x82, 0x11, 0x04, 0x00, 0x01),
        )
    }

    private fun uac1ContinuousRange(): ByteArray = descriptors(
        deviceDescriptor(),
        configurationDescriptor(interfaces = 2),
        interfaceDescriptor(number = 1, altSetting = 0, endpoints = 0, subclass = 1, protocol = 0),
        descriptor(0x0C, 0x24, 0x02, 0x01, 0x01, 0x01, 0x00, 0x02, 0x03, 0x00, 0x00, 0x00),
        descriptor(0x09, 0x24, 0x03, 0x03, 0x01, 0x03, 0x00, 0x01, 0x00),
        interfaceDescriptor(number = 2, altSetting = 1, endpoints = 1, subclass = 2, protocol = 0),
        descriptor(0x07, 0x24, 0x01, 0x01, 0x00, 0x01, 0x00),
        // 0 表示连续范围，后给上下限
        descriptor(0x0E, 0x24, 0x02, 0x01, 0x02, 0x03, 0x18, 0x00, 0x44, 0xAC, 0x00, 0x00, 0x77, 0x01),
        descriptor(0x09, 0x05, 0x01, 0x01, 0xC0, 0x00, 0x01, 0x00, 0x00),
    )

    private fun uac1WithoutFormatDescriptor(): ByteArray = descriptors(
        deviceDescriptor(),
        configurationDescriptor(interfaces = 2),
        interfaceDescriptor(number = 1, altSetting = 0, endpoints = 0, subclass = 1, protocol = 0),
        interfaceDescriptor(number = 2, altSetting = 1, endpoints = 1, subclass = 2, protocol = 0),
        descriptor(0x07, 0x24, 0x01, 0x01, 0x00, 0x01, 0x00),
        descriptor(0x09, 0x05, 0x01, 0x01, 0xC0, 0x00, 0x01, 0x00, 0x00),
    )

    /** UAC2 播放设备；0b11 才可写 */
    private fun uac2Async(volumeControls: Int = 0x0F): ByteArray {
        val featureEntries = (0 until 3).flatMap {
            listOf(volumeControls and 0xFF, 0x00, 0x00, 0x00)
        }
        return descriptors(
            deviceDescriptor(),
            configurationDescriptor(interfaces = 2),
            interfaceDescriptor(number = 1, altSetting = 0, endpoints = 0, subclass = 1, protocol = 0x20),
            descriptor(0x09, 0x24, 0x01, 0x00, 0x02, 0x01, 0x00, 0x00, 0x01),
            // 时钟源 41，采样率可写
            descriptor(0x08, 0x24, 0x0A, 0x29, 0x01, 0x03, 0x00, 0x00),
            // 选择器 42 引脚 1 接时钟源 41
            descriptor(0x08, 0x24, 0x0B, 0x2A, 0x01, 0x29, 0x03, 0x00),
            // 输入终端 1：bCSourceID = 42
            descriptor(
                0x11, 0x24, 0x02, 0x01, 0x01, 0x01, 0x00, 0x2A, 0x02,
                0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            ),
            descriptor(
                *(intArrayOf(0x12, 0x24, 0x06, 0x02, 0x01) + featureEntries.toIntArray() + intArrayOf(0x00)),
            ),
            // 输出终端 3 由 FU2 馈入
            descriptor(0x0C, 0x24, 0x03, 0x03, 0x01, 0x03, 0x00, 0x02, 0x2A, 0x00, 0x00, 0x00),
            interfaceDescriptor(number = 2, altSetting = 1, endpoints = 1, subclass = 2, protocol = 0x20),
            // AS_GENERAL：Type I，PCM，2 声道
            descriptor(
                0x10, 0x24, 0x01, 0x01, 0x00, 0x01,
                0x01, 0x00, 0x00, 0x00, 0x02, 0x03, 0x00, 0x00, 0x00, 0x00,
            ),
            // Type I 格式：4 字节子槽、32 位
            descriptor(0x06, 0x24, 0x02, 0x01, 0x04, 0x20),
            descriptor(0x07, 0x05, 0x01, 0x05, 0x60, 0x00, 0x04),
            descriptor(0x07, 0x05, 0x81, 0x11, 0x04, 0x00, 0x04),
        )
    }

    private fun deviceDescriptor(): ByteArray = descriptor(
        0x12, 0x01, 0x00, 0x02, 0x00, 0x00, 0x00, 0x40, 0x46, 0x2C,
        0x86, 0x06, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
    )

    private fun configurationDescriptor(interfaces: Int): ByteArray = descriptor(
        0x09, 0x02, 0x00, 0x00, interfaces, 0x01, 0x00, 0x80, 0x32,
    )

    private fun interfaceDescriptor(
        number: Int,
        altSetting: Int,
        endpoints: Int,
        subclass: Int,
        protocol: Int,
    ): ByteArray = descriptor(
        0x09, 0x04, number, altSetting, endpoints, 0x01, subclass, protocol, 0x00,
    )
}

private fun descriptor(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

private fun descriptors(vararg parts: ByteArray): ByteArray =
    parts.fold(ByteArray(0)) { acc, part -> acc + part }
