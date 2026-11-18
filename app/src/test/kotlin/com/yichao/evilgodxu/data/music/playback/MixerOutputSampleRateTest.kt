package com.yichao.evilgodxu.data.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 混音模式输出速率的取值口径复核。
 *
 * 这三条判据决定了面板上「输出采样率」与「重采样」两行是否可信，判错不会报错，只会把系统重采样
 * 报成未发生，故逐条断言：蓝牙只认编码速率、其余通路取主输出流速率、设备声明不了该档时留空。
 */
class MixerOutputSampleRateTest {

    @Test
    fun `蓝牙按协商出的编码速率报输出速率`() {
        // 主输出那条流报的仍是扬声器的 48k，与 A2DP 输出流无关，故不能被它覆盖
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
        // 设备只声明 96k：平台会另开一条按设备档位跑的输出流，主输出速率不落在它身上，只能留空
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
