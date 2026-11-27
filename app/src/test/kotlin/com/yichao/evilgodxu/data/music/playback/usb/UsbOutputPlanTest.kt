package com.yichao.evilgodxu.data.music.playback.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 不能逐位原样送出就退回平台路径 */
class UsbOutputPlanTest {

    @Test
    fun `挑选仍能容纳源的最小位深`() {
        val plan = planUsbOutput(
            sampleRate = 44100,
            channelCount = 2,
            sourceBitDepth = 24,
            candidates = listOf(
                format(altSetting = 2, subslotBytes = 4, bitResolution = 32),
                format(altSetting = 1, subslotBytes = 3, bitResolution = 24),
            ),
        )

        // 止步于源格式，宽槽无收益
        assertEquals(1, plan?.altSetting)
    }

    // 同有效位深取更窄子槽
    @Test
    fun `位深相同时优先更窄的子槽`() {
        val plan = planUsbOutput(
            sampleRate = 44100,
            channelCount = 2,
            sourceBitDepth = 24,
            candidates = listOf(
                format(altSetting = 4, subslotBytes = 4, bitResolution = 24),
                format(altSetting = 1, subslotBytes = 3, bitResolution = 24),
            ),
        )

        assertEquals(1, plan?.altSetting)
    }

    // 有效位深不足，宽槽也丢低位
    @Test
    fun `拒绝比源更浅的位深`() {
        val plan = planUsbOutput(
            sampleRate = 96000,
            channelCount = 2,
            sourceBitDepth = 32,
            candidates = listOf(
                format(subslotBytes = 4, bitResolution = 24),
                format(altSetting = 2, subslotBytes = 2, bitResolution = 16),
            ),
        )

        assertNull(plan)
    }

    // 16 位源优先进 24 位槽，留衰减余量
    @Test
    fun `16 位源优先留出余量的 24 位模式`() {
        val plan = planUsbOutput(
            sampleRate = 48000,
            channelCount = 2,
            sourceBitDepth = 16,
            candidates = listOf(
                format(altSetting = 1, subslotBytes = 2, bitResolution = 16),
                format(altSetting = 2, subslotBytes = 3, bitResolution = 24),
                format(altSetting = 3, subslotBytes = 4, bitResolution = 32),
            ),
        )

        assertEquals(2, plan?.altSetting)
    }

    // 只声明 16 位时退回够装即可
    @Test
    fun `没有更宽档位时退回 16 位模式`() {
        val plan = planUsbOutput(
            sampleRate = 48000,
            channelCount = 2,
            sourceBitDepth = 16,
            candidates = listOf(format(altSetting = 1, subslotBytes = 2, bitResolution = 16)),
        )

        assertEquals(1, plan?.altSetting)
    }

    // 声道不匹配不映射，只送原样
    @Test
    fun `声道数不匹配被拒绝`() {
        val plan = planUsbOutput(
            sampleRate = 44100,
            channelCount = 1,
            sourceBitDepth = 16,
            candidates = listOf(format(channels = 2)),
        )

        assertNull(plan)
    }

    @Test
    fun `非 PCM 格式被拒绝`() {
        val plan = planUsbOutput(
            sampleRate = 44100,
            channelCount = 2,
            sourceBitDepth = 24,
            candidates = listOf(format(formatTag = 0)),
        )

        assertNull(plan)
    }

    // UAC2 无声明档位不得判不支持
    @Test
    fun `未声明采样率的格式可接受`() {
        val plan = planUsbOutput(
            sampleRate = 176400,
            channelCount = 2,
            sourceBitDepth = 24,
            candidates = listOf(format(rates = emptyList())),
        )

        assertEquals(1, plan?.altSetting)
    }

    @Test
    fun `声明的采样率不含源采样率时被拒绝`() {
        val plan = planUsbOutput(
            sampleRate = 176400,
            channelCount = 2,
            sourceBitDepth = 24,
            candidates = listOf(format(rates = listOf(44100, 48000))),
        )

        assertNull(plan)
    }

    @Test
    fun `连续范围按边界判定`() {
        val candidates = listOf(format(rates = emptyList(), continuousRates = true, minRate = 44100, maxRate = 96000))

        assertEquals(
            1,
            planUsbOutput(88200, 2, 24, candidates)?.altSetting,
        )
        assertNull(planUsbOutput(192000, 2, 24, candidates))
    }

    @Test
    fun `空或无效输入返回空`() {
        assertNull(planUsbOutput(44100, 2, 24, emptyList()))
        assertNull("采样率未知时不挑格式", planUsbOutput(0, 2, 24, listOf(format())))
        assertNull("声道数未知时不挑格式", planUsbOutput(44100, 0, 24, listOf(format())))
    }

    private fun format(
        altSetting: Int = 1,
        channels: Int = 2,
        subslotBytes: Int = 3,
        bitResolution: Int = 24,
        formatTag: Int = 1,
        rates: List<Int> = listOf(44100, 48000),
        continuousRates: Boolean = false,
        minRate: Int = 0,
        maxRate: Int = 0,
    ): UsbAudioOutputFormat = UsbAudioOutputFormat(
        interfaceNumber = 2,
        altSetting = altSetting,
        endpointAddress = 0x01,
        maxPacketBytes = 192,
        interval = 1,
        syncType = UsbAudioSyncType.ASYNCHRONOUS,
        feedbackEndpoint = 0x81,
        feedbackMaxPacketBytes = 4,
        feedbackInterval = 1,
        formatTag = formatTag,
        channels = channels,
        subslotBytes = subslotBytes,
        bitResolution = bitResolution,
        continuousRates = continuousRates,
        minRate = minRate,
        maxRate = maxRate,
        rates = rates,
        rateControlOnEndpoint = false,
        clockId = 41,
        terminalLink = 1,
    )
}
