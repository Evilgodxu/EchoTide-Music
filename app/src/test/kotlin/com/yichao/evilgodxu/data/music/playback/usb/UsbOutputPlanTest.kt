package com.yichao.evilgodxu.data.music.playback.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 播放格式的挑选。
 *
 * 这一层的判据是"能不能逐位原样送出"，故每个用例都对着**不该被选中**的搭配构造：能出声但需要重采样、
 * 丢低位或做声道映射的搭配必须一律落空，让调用方退回平台路径。宁可退回，也不要静默降级出声——
 * "少了几位"在听感上未必立刻可辨，用户只会觉得哪里不对却说不上来。
 */
class UsbOutputPlanTest {

    @Test
    fun picksTheLeastBitDepthThatStillHoldsTheSource() {
        val plan = planUsbOutput(
            sampleRate = 44100,
            channelCount = 2,
            sourceBitDepth = 24,
            candidates = listOf(
                format(altSetting = 2, subslotBytes = 4, bitResolution = 32),
                format(altSetting = 1, subslotBytes = 3, bitResolution = 24),
            ),
        )

        // 本项目直出的口径是"止步于源格式"：32 位通道装 24 位源并不带来保真收益
        assertEquals(1, plan?.altSetting)
    }

    // 有效位深相同时取更窄的子槽：3 字节槽装 24 位，比 4 字节槽少一个补零字节
    @Test
    fun prefersTheNarrowerSlotOnEqualBitDepth() {
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

    // 子槽再宽也救不了有效位深不足：32 位源进 24 位通道，低八位注定被丢
    @Test
    fun rejectsBitDepthsTooShallowForTheSource() {
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

    // 16 位源优先落在 16 位通道上，而不是设备最宽的那一档
    @Test
    fun sixteenBitSourcePrefersASixteenBitMode() {
        val plan = planUsbOutput(
            sampleRate = 48000,
            channelCount = 2,
            sourceBitDepth = 16,
            candidates = listOf(
                format(altSetting = 1, subslotBytes = 2, bitResolution = 16),
                format(altSetting = 2, subslotBytes = 4, bitResolution = 24),
                format(altSetting = 3, subslotBytes = 4, bitResolution = 32),
            ),
        )

        assertEquals(1, plan?.altSetting)
    }

    // 声道数不匹配时不做映射：映射属于另一条链路的事，这里只送原样数据
    @Test
    fun rejectsChannelCountMismatch() {
        val plan = planUsbOutput(
            sampleRate = 44100,
            channelCount = 1,
            sourceBitDepth = 16,
            candidates = listOf(format(channels = 2)),
        )

        assertNull(plan)
    }

    @Test
    fun rejectsFormatsThatAreNotPcm() {
        val plan = planUsbOutput(
            sampleRate = 44100,
            channelCount = 2,
            sourceBitDepth = 24,
            candidates = listOf(format(formatTag = 0)),
        )

        assertNull(plan)
    }

    // UAC2 的档位不在描述符里：未声明任何档位时不得判为不支持，否则所有 UAC2 设备都会被挡在门外
    @Test
    fun acceptsFormatsThatDeclareNoRatesAtAll() {
        val plan = planUsbOutput(
            sampleRate = 176400,
            channelCount = 2,
            sourceBitDepth = 24,
            candidates = listOf(format(rates = emptyList())),
        )

        assertEquals(1, plan?.altSetting)
    }

    @Test
    fun rejectsDeclaredRatesThatLackTheSourceRate() {
        val plan = planUsbOutput(
            sampleRate = 176400,
            channelCount = 2,
            sourceBitDepth = 24,
            candidates = listOf(format(rates = listOf(44100, 48000))),
        )

        assertNull(plan)
    }

    @Test
    fun continuousRangeIsJudgedByItsBounds() {
        val candidates = listOf(format(rates = emptyList(), continuousRates = true, minRate = 44100, maxRate = 96000))

        assertEquals(
            1,
            planUsbOutput(88200, 2, 24, candidates)?.altSetting,
        )
        assertNull(planUsbOutput(192000, 2, 24, candidates))
    }

    @Test
    fun returnsNullOnEmptyOrNonsenseInput() {
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
