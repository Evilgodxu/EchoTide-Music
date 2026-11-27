package com.yichao.evilgodxu.data.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 蓝牙认编码速率，其余取主输出流速率 */
class MixerOutputSampleRateTest {

    @Test
    fun `蓝牙按协商出的编码速率报输出速率`() {
        // 主输出仍报扬声器 48k，不得覆盖
        assertEquals(
            44_100,
            mixerOutputSampleRateHz(
                device(OutputDeviceKind.BLUETOOTH, sampleRates = listOf(48_000), codecRate = 44_100),
                platformRate = 48_000,
            ),
        )
    }

    @Test
    fun `蓝牙读不到协商结果时不拿主输出速率顶替`() {
        assertNull(
            mixerOutputSampleRateHz(
                device(OutputDeviceKind.BLUETOOTH, sampleRates = listOf(48_000)),
                platformRate = 48_000,
            ),
        )
    }

    @Test
    fun `非蓝牙通路取主输出流的速率`() {
        assertEquals(
            48_000,
            mixerOutputSampleRateHz(
                device(OutputDeviceKind.USB, sampleRates = listOf(44_100, 48_000, 96_000)),
                platformRate = 48_000,
            ),
        )
    }

    @Test
    fun `设备声明不了主输出那一档时不报速率`() {
        // 设备只声明 96k 时留空
        assertNull(
            mixerOutputSampleRateHz(
                device(OutputDeviceKind.USB, sampleRates = listOf(96_000)),
                platformRate = 48_000,
            ),
        )
    }

    @Test
    fun `设备未上报档位时仍取主输出速率`() {
        assertEquals(
            48_000,
            mixerOutputSampleRateHz(device(OutputDeviceKind.SPEAKER), platformRate = 48_000),
        )
    }

    @Test
    fun `主输出速率读不到时留空`() {
        assertNull(mixerOutputSampleRateHz(device(OutputDeviceKind.WIRED, listOf(48_000)), null))
    }

    @Test
    fun `判定不出输出设备时按主输出速率`() {
        assertEquals(48_000, mixerOutputSampleRateHz(null, platformRate = 48_000))
    }

    private fun device(
        kind: OutputDeviceKind,
        sampleRates: List<Int> = emptyList(),
        codecRate: Int? = null,
    ): OutputDeviceInfo = OutputDeviceInfo(
        kind = kind,
        name = null,
        address = null,
        supportedSampleRates = sampleRates,
        sampleRateSource = SampleRateSource.PLATFORM,
        channelCount = null,
        supportedEncodings = emptyList(),
        bluetooth = BluetoothLinkInfo(
            linkType = BluetoothLinkType.CLASSIC,
            deviceClass = null,
            codec = codecRate?.let {
                BluetoothCodecInfo(
                    name = "SBC",
                    sampleRateHz = it,
                    bitsPerSample = 16,
                    channelMode = BluetoothChannelMode.STEREO,
                )
            },
        ),
    )
}
