package com.yichao.evilgodxu.data.settings

import org.junit.Assert.assertEquals
import org.junit.Test

// 播放设置的默认值与缺失键回落规则：默认值一旦被改动，用户首次安装看到的开关状态就会变
class PlaybackSettingsTest {

    @Test
    fun `默认值锁定首次安装时的开关状态`() {
        val settings = PlaybackSettings()

        assertEquals(false, settings.miniPlayerEnabled)
        assertEquals(true, settings.wordByWordRendering)
        assertEquals(true, settings.swipeToChangeTrack)
        assertEquals(false, settings.backgroundFlow)
        assertEquals(false, settings.usbDirectOutput)
        assertEquals(false, settings.usbExclusiveOutput)
    }

    @Test
    fun `全部键缺失时回落到默认值`() {
        val settings = playbackSettingsOf(
            miniPlayerEnabled = null,
            wordByWordRendering = null,
            swipeToChangeTrack = null,
            backgroundFlow = null,
            usbDirectOutput = null,
            usbExclusiveOutput = null,
        )

        assertEquals(PlaybackSettings(), settings)
    }

    @Test
    fun `已落盘的值优先于默认值`() {
        val settings = playbackSettingsOf(
            miniPlayerEnabled = true,
            wordByWordRendering = false,
            swipeToChangeTrack = false,
            backgroundFlow = true,
            usbDirectOutput = true,
            usbExclusiveOutput = false,
        )

        assertEquals(true, settings.miniPlayerEnabled)
        assertEquals(false, settings.wordByWordRendering)
        assertEquals(false, settings.swipeToChangeTrack)
        assertEquals(true, settings.backgroundFlow)
        assertEquals(true, settings.usbDirectOutput)
        assertEquals(false, settings.usbExclusiveOutput)
    }

    @Test
    fun `部分键缺失时逐项回落而不影响其余键`() {
        val settings = playbackSettingsOf(
            miniPlayerEnabled = true,
            wordByWordRendering = null,
            swipeToChangeTrack = null,
            backgroundFlow = null,
            usbDirectOutput = null,
            usbExclusiveOutput = true,
        )

        assertEquals(true, settings.miniPlayerEnabled)
        assertEquals(true, settings.wordByWordRendering)
        assertEquals(true, settings.swipeToChangeTrack)
        assertEquals(false, settings.backgroundFlow)
        assertEquals(false, settings.usbDirectOutput)
        assertEquals(true, settings.usbExclusiveOutput)
    }
}
