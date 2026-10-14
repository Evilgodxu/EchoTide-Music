package com.yichao.evilgodxu.data.music.playback.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * USB 音频描述符解析。
 *
 * 用例全部用合成的描述符字节流构造，不依赖真机：解析的判据是「设备声明了什么」，与实际能否建流无关，
 * 故各种形态的声明（UAC1/UAC2、离散与连续速率、缺格式描述、畸形长度）都能在 JVM 上跑一遍。
 *
 * 重点是四处容易读错的地方：UAC2 的采样率不在描述符里而要经终端回溯到时钟实体；UAC2 的控制位是 2 位
 * 字段且 0b11 才可写；UAC1 的反馈端点没有用途位、只能靠地址配对；畸形长度必须停止推进而非读越界。
 */
class UsbAudioDescriptorsTest {

    // ---------------------------------------------------------------- UAC1

    @Test
    fun uac1DiscreteRatesAreParsedWithVolumeAndFeedback() {
        val device = UsbAudioDescriptorParser.parse(uac1DiscreteRates())

        assertEquals(1, device.uacVersion)
        assertEquals(1, device.controlInterfaceNumber)
        assertEquals(1, device.outputs.size)

        val format = device.outputs.single()
        assertEquals(2, format.interfaceNumber)
        // alt 0 是零带宽、没有端点，不得被当成可播放目标
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
        // UAC1 的采样率控制声明在类特定端点描述符上
        assertTrue(format.rateControlOnEndpoint)
        assertEquals(1, format.terminalLink)
        assertEquals(0, format.clockId)
        assertEquals(6, format.frameBytes)
    }

    @Test
    fun uac1FeatureUnitIsFoundThroughTheTerminalChain() {
        val device = UsbAudioDescriptorParser.parse(uac1DiscreteRates())

        val feature = device.playbackFeatureUnit(device.outputs.single())
        assertEquals(2, feature?.unitId)
        assertTrue(feature!!.masterMute)
        assertTrue(feature.masterVolume)
        assertEquals(listOf(true, true), feature.channelVolume)
    }

    // 未声明的档位不得被判为支持：候选只按声明挑，试错建轨会拖坏设备
    @Test
    fun uac1UndeclaredRateIsNotSupported() {
        val format = UsbAudioDescriptorParser.parse(uac1DiscreteRates()).outputs.single()

        assertTrue(format.supportsRate(44100))
        assertTrue(format.supportsRate(48000))
        assertFalse(format.supportsRate(96000))
        assertFalse(format.supportsRate(0))
    }

    @Test
    fun uac1ContinuousRangeKeepsDeclaredBounds() {
        val format = UsbAudioDescriptorParser.parse(uac1ContinuousRange()).outputs.single()

        assertTrue(format.continuousRates)
        assertEquals(44100, format.minRate)
        assertEquals(96000, format.maxRate)
        assertTrue(format.supportsRate(88200))
        assertFalse(format.supportsRate(192000))
    }

    // ---------------------------------------------------------------- UAC2

    @Test
    fun uac2RateComesFromTheClockReachedThroughTheTerminal() {
        val device = UsbAudioDescriptorParser.parse(uac2Async())
        val format = device.outputs.single()

        assertEquals(2, device.uacVersion)
        // 输入终端声明时钟 42，选择器把 42 接到时钟源 41
        assertEquals(42, format.clockId)
        assertEquals(41, device.resolveClockSource(format.clockId) { 0 })
        assertTrue(device.clock(41)!!.frequencyWritable)
        assertTrue(device.clock(42)!!.selectorWritable)
    }

    @Test
    fun uac2FormatCarriesSubslotDepthAndFeedback() {
        val format = UsbAudioDescriptorParser.parse(uac2Async()).outputs.single()

        assertEquals(1, format.formatTag)
        assertEquals(2, format.channels)
        assertEquals(4, format.subslotBytes)
        assertEquals(32, format.bitResolution)
        assertEquals(8, format.frameBytes)
        assertEquals(UsbAudioSyncType.ASYNCHRONOUS, format.syncType)
        assertEquals(0x81, format.feedbackEndpoint)
        // UAC2 的档位由时钟在运行期应答，描述符里为空是正常的
        assertTrue(format.rates.isEmpty())
        assertFalse(format.rateControlOnEndpoint)
    }

    @Test
    fun uac2WritableVolumeIsClaimed() {
        val device = UsbAudioDescriptorParser.parse(uac2Async(volumeControls = 0x0F))

        val feature = device.playbackFeatureUnit(device.outputs.single())
        assertEquals(2, feature?.unitId)
        assertTrue(feature!!.masterVolume)
        assertTrue(feature.masterMute)
        assertEquals(listOf(true, true), feature.channelVolume)
    }

    // 0b10 的 2 位字段不是 0b11：不可写的音量不得被当成可用控制，否则设音量会静默失败
    @Test
    fun uac2ReadOnlyVolumeYieldsNoUsableControl() {
        val device = UsbAudioDescriptorParser.parse(uac2Async(volumeControls = 0x02))

        assertNull(device.playbackFeatureUnit(device.outputs.single()))
    }

    // ---------------------------------------------------------------- 边界

    // 回溯遇到未建模的单元（混音器、选择器）即停止：越过它就无法确定哪个 Feature Unit 服务于本链路
    @Test
    fun featureUnitBehindAnUnknownUnitIsNotClaimed() {
        val device = UsbAudioDescriptorParser.parse(uac1DiscreteRates(featureFedByMixer = true))

        assertNull(device.playbackFeatureUnit(device.outputs.single()))
    }

    @Test
    fun onlyTheFirstConfigurationIsParsed() {
        val raw = uac1DiscreteRates() +
            descriptor(0x09, 0x02, 0x00, 0x00, 0x01, 0x01, 0x00, 0x80, 0x32)

        assertEquals(1, UsbAudioDescriptorParser.parse(raw).outputs.size)
    }

    // 长度不合法（含 0）时必须停止推进，否则会读到流之外并抛越界
    @Test
    fun malformedLengthStopsInsteadOfReadingPastTheEnd() {
        val truncated = descriptor(0x12, 0x01) + descriptor(0x00, 0x04)
        val device = UsbAudioDescriptorParser.parse(truncated)

        assertEquals(0, device.uacVersion)
        assertEquals(-1, device.controlInterfaceNumber)
        assertTrue(device.outputs.isEmpty())
    }

    @Test
    fun missingFormatDescriptorYieldsNoPlayableOutput() {
        val device = UsbAudioDescriptorParser.parse(uac1WithoutFormatDescriptor())

        assertEquals(1, device.uacVersion)
        assertTrue(device.outputs.isEmpty())
    }

    // ---------------------------------------------------------------- 速率范围展开

    @Test
    fun rangeExpandsToCommonAudioRatesOnly() {
        // 单区间 44100..96000，步长 0（不限制）
        val data = descriptor(
            0x01, 0x00,
            0x44, 0xAC, 0x00, 0x00,
            0x00, 0x77, 0x01, 0x00,
            0x00, 0x00, 0x00, 0x00,
        )

        assertEquals(listOf(44100, 48000, 88200, 96000), usbAudioRatesFromRange(data, 0, data.size))
    }

    @Test
    fun zeroWidthRangeKeepsItsSingleRate() {
        val data = descriptor(
            0x01, 0x00,
            0x80, 0xBB, 0x00, 0x00,
            0x80, 0xBB, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
        )

        assertEquals(listOf(48000), usbAudioRatesFromRange(data, 0, data.size))
    }

    @Test
    fun rangeOutsideTheAudioBandsYieldsNothing() {
        val data = descriptor(
            0x01, 0x00,
            0x10, 0x27, 0x00, 0x00,
            0x20, 0x4E, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
        )

        assertTrue(usbAudioRatesFromRange(data, 0, data.size).isEmpty())
    }

    @Test
    fun truncatedRangeReplyIsIgnored() {
        val declared = descriptor(0x02, 0x00, 0x44, 0xAC, 0x00, 0x00)

        assertTrue(usbAudioRatesFromRange(declared, 0, declared.size).isEmpty())
    }

    // ---------------------------------------------------------------- 合成描述符

    /**
     * UAC1 播放设备：输入终端 1 → Feature Unit 2 → 输出终端 3，另有一个离散档位的流接口 alt 1。
     *
     * [featureFedByMixer] 为真时在两者之间插入一个选择器单元（子类 5），Feature Unit 改由它馈入——
     * 此时回溯既走不通也不该走回退分支（Feature Unit 已不直接连在流终端上），故应判为拿不到音量控制。
     */
    private fun uac1DiscreteRates(featureFedByMixer: Boolean = false): ByteArray {
        val featureSource = if (featureFedByMixer) 0x05 else 0x01
        return descriptors(
            deviceDescriptor(),
            configurationDescriptor(interfaces = 2),
            interfaceDescriptor(number = 1, altSetting = 0, endpoints = 0, subclass = 1, protocol = 0),
            // AC 头：本解析器不读它，但实物必有，留着以免合成流与真机结构相差过远
            descriptor(0x09, 0x24, 0x01, 0x00, 0x01, 0x0A, 0x00, 0x01, 0x02),
            descriptor(0x0C, 0x24, 0x02, 0x01, 0x01, 0x01, 0x00, 0x02, 0x03, 0x00, 0x00, 0x00),
            if (featureFedByMixer) {
                // 选择器单元 5，由输入终端 1 馈入
                descriptor(0x08, 0x24, 0x05, 0x05, 0x01, 0x01, 0x00, 0x00)
            } else {
                ByteArray(0)
            },
            // Feature Unit 2：主控制 0x03（静音+音量），两个声道各 0x02（音量）；
            // 长度 7 + 控制宽度 × 条目数，条目为「主控制 + 每声道一个」，末尾另有一个 iFeature
            descriptor(0x0A, 0x24, 0x06, 0x02, featureSource, 0x01, 0x03, 0x02, 0x02, 0x00),
            // 输出终端 3 由 Feature Unit 2 馈入
            descriptor(0x09, 0x24, 0x03, 0x03, 0x01, 0x03, 0x00, 0x02, 0x00),
            interfaceDescriptor(number = 2, altSetting = 0, endpoints = 0, subclass = 2, protocol = 0),
            interfaceDescriptor(number = 2, altSetting = 1, endpoints = 1, subclass = 2, protocol = 0),
            descriptor(0x07, 0x24, 0x01, 0x01, 0x00, 0x01, 0x00),
            // Type I：2 声道、3 字节子槽、24 位，两个离散档位 44100/48000
            descriptor(
                0x0E, 0x24, 0x02, 0x01, 0x02, 0x03, 0x18, 0x02,
                0x44, 0xAC, 0x00, 0x80, 0xBB, 0x00,
            ),
            // 等时 OUT、异步，bSynchAddress 指向反馈端点
            descriptor(0x09, 0x05, 0x01, 0x05, 0xC0, 0x00, 0x01, 0x00, 0x82),
            // 类特定端点：bmAttributes 位 0 声明采样率可设
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
        // bSamFreqType = 0 表示连续范围，随后给下限与上限
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

    /**
     * UAC2 播放设备：时钟源 41 → 时钟选择器 42 → 输入终端 1，另有 Feature Unit 2 与输出终端 3。
     * [volumeControls] 为 Feature Unit 的四组 2 位控制字段取值，0b11 才表示宿主可写。
     */
    private fun uac2Async(volumeControls: Int = 0x0F): ByteArray {
        val featureEntries = (0 until 3).flatMap {
            listOf(volumeControls and 0xFF, 0x00, 0x00, 0x00)
        }
        return descriptors(
            deviceDescriptor(),
            configurationDescriptor(interfaces = 2),
            interfaceDescriptor(number = 1, altSetting = 0, endpoints = 0, subclass = 1, protocol = 0x20),
            descriptor(0x09, 0x24, 0x01, 0x00, 0x02, 0x01, 0x00, 0x00, 0x01),
            // 时钟源 41：bmControls 0x03 表示采样率可写
            descriptor(0x08, 0x24, 0x0A, 0x29, 0x01, 0x03, 0x00, 0x00),
            // 时钟选择器 42：引脚 1 接时钟源 41
            descriptor(0x08, 0x24, 0x0B, 0x2A, 0x01, 0x29, 0x03, 0x00),
            // 输入终端 1：bCSourceID = 42
            descriptor(
                0x11, 0x24, 0x02, 0x01, 0x01, 0x01, 0x00, 0x2A, 0x02,
                0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            ),
            descriptor(
                *(intArrayOf(0x12, 0x24, 0x06, 0x02, 0x01) + featureEntries.toIntArray() + intArrayOf(0x00)),
            ),
            // 输出终端 3：由 Feature Unit 2 馈入，bCSourceID = 42
            descriptor(0x0C, 0x24, 0x03, 0x03, 0x01, 0x03, 0x00, 0x02, 0x2A, 0x00, 0x00, 0x00),
            interfaceDescriptor(number = 2, altSetting = 1, endpoints = 1, subclass = 2, protocol = 0x20),
            // AS_GENERAL：终端链接 1，格式类型 I，bmFormats 位 0 为 PCM，2 声道
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
