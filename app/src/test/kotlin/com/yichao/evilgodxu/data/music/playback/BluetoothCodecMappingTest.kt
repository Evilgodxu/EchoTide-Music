package com.yichao.evilgodxu.data.music.playback

import android.bluetooth.BluetoothCodecConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 位掩码与整数标识须逐值映射正确 */
@Suppress("DEPRECATION")
class BluetoothCodecMappingTest {

    @Test
    fun `每个采样率标志映射到自身采样率`() {
        assertEquals(44_100, BluetoothCodecResolver.sampleRateHz(BluetoothCodecConfig.SAMPLE_RATE_44100))
        assertEquals(48_000, BluetoothCodecResolver.sampleRateHz(BluetoothCodecConfig.SAMPLE_RATE_48000))
        assertEquals(88_200, BluetoothCodecResolver.sampleRateHz(BluetoothCodecConfig.SAMPLE_RATE_88200))
        assertEquals(96_000, BluetoothCodecResolver.sampleRateHz(BluetoothCodecConfig.SAMPLE_RATE_96000))
        assertEquals(176_400, BluetoothCodecResolver.sampleRateHz(BluetoothCodecConfig.SAMPLE_RATE_176400))
        assertEquals(192_000, BluetoothCodecResolver.sampleRateHz(BluetoothCodecConfig.SAMPLE_RATE_192000))
    }

    @Test
    fun `每个位深标志映射到自身位数`() {
        assertEquals(16, BluetoothCodecResolver.bitsPerSample(BluetoothCodecConfig.BITS_PER_SAMPLE_16))
        assertEquals(24, BluetoothCodecResolver.bitsPerSample(BluetoothCodecConfig.BITS_PER_SAMPLE_24))
        assertEquals(32, BluetoothCodecResolver.bitsPerSample(BluetoothCodecConfig.BITS_PER_SAMPLE_32))
    }

    @Test
    fun `标志为空时不产出取值`() {
        // 非单一取值一律留空
        assertNull(BluetoothCodecResolver.sampleRateHz(BluetoothCodecConfig.SAMPLE_RATE_NONE))
        assertNull(BluetoothCodecResolver.bitsPerSample(BluetoothCodecConfig.BITS_PER_SAMPLE_NONE))
        assertNull(BluetoothCodecResolver.channelMode(BluetoothCodecConfig.CHANNEL_MODE_NONE))
    }

    @Test
    fun `每种声道模式映射到自身名称`() {
        assertEquals(
            BluetoothChannelMode.MONO,
            BluetoothCodecResolver.channelMode(BluetoothCodecConfig.CHANNEL_MODE_MONO),
        )
        assertEquals(
            BluetoothChannelMode.STEREO,
            BluetoothCodecResolver.channelMode(BluetoothCodecConfig.CHANNEL_MODE_STEREO),
        )
    }

    @Test
    fun `旧版编码类型映射到平台名称`() {
        assertEquals("SBC", BluetoothCodecResolver.legacyCodecName(BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC))
        assertEquals("AAC", BluetoothCodecResolver.legacyCodecName(BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC))
        assertEquals("aptX", BluetoothCodecResolver.legacyCodecName(BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX))
        assertEquals(
            "aptX HD",
            BluetoothCodecResolver.legacyCodecName(BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX_HD),
        )
        assertEquals("LDAC", BluetoothCodecResolver.legacyCodecName(BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC))
        assertEquals("LC3", BluetoothCodecResolver.legacyCodecName(BluetoothCodecConfig.SOURCE_CODEC_TYPE_LC3))
        assertEquals("Opus", BluetoothCodecResolver.legacyCodecName(BluetoothCodecConfig.SOURCE_CODEC_TYPE_OPUS))
    }

    @Test
    fun `未知编码类型不产出名称`() {
        // 厂商编码器不取名，宁可留空
        assertNull(BluetoothCodecResolver.legacyCodecName(BluetoothCodecConfig.SOURCE_CODEC_TYPE_INVALID))
        assertNull(BluetoothCodecResolver.legacyCodecName(100))
    }
}
