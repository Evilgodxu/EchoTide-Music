package com.yichao.evilgodxu.data.music.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 展示用格式名的判定：扩展名能定案就用扩展名，容器扩展名必须让位给 mime。
 *
 * 这条规则同时决定「有无损失」与「无损升级提示」（两处都读格式名），故容器名冒充格式名的代价
 * 不止是名字难听：一首无损的 ALAC 会被判成有损。
 */
class FormatDisplayNameTest {

    @Test
    fun singleCodecExtensionNamesTheFormat() {
        assertEquals("FLAC", formatDisplayName("/music/a.flac", "audio/flac"))
        assertEquals("MP3", formatDisplayName("/music/a.mp3", "audio/mpeg"))
        assertEquals("DSF", formatDisplayName("/music/a.dsf", null))
        assertEquals("WAV", formatDisplayName("/music/a.wav", null))
        // .mpeg 文件即 MPEG 音频，展示名统一为 MP3（与曲库分类里的映射同源）
        assertEquals("MP3", formatDisplayName("/music/a.mpeg", null))
        assertEquals("FLAC", formatDisplayName("/music/a.FLAC", null))
    }

    @Test
    fun containerExtensionDefersToMime() {
        // .m4a 内可能是 AAC 也可能是 ALAC，扩展名说不出码流
        assertEquals("ALAC", formatDisplayName("/music/a.m4a", "audio/alac"))
        assertEquals("AAC", formatDisplayName("/music/a.m4a", "audio/mp4"))
        assertEquals("OPUS", formatDisplayName("/music/a.ogg", "audio/opus"))
        assertEquals("FLAC", formatDisplayName("/music/a.mp4", "audio/flac"))
    }

    @Test
    fun containerExtensionFallsBackToItsOwnName() {
        // mime 也定不出时留着容器名：信息量再少也好过把整行留空
        assertEquals("M4A", formatDisplayName("/music/a.m4a", null))
        assertEquals("CAF", formatDisplayName("/music/a.caf", null))
    }

    @Test
    fun missingExtensionUsesMime() {
        assertEquals("FLAC", formatDisplayName(null, "audio/flac"))
        assertEquals("MP3", formatDisplayName("/music/no-extension", "audio/mpeg"))
        assertEquals("ALAC", formatDisplayName("/music/no-extension", "audio/alac"))
        assertNull("两边都定不出时不产出条目", formatDisplayName(null, null))
    }

    @Test
    fun alacCountsAsLosslessWhereContainerNameDoesNot() {
        // 这一条是整条规则的由来：容器名认不出无损，格式名认得出
        assertTrue(isLosslessFormatName(formatDisplayName("/music/a.m4a", "audio/alac")!!))
        assertFalse(isLosslessFormatName(formatDisplayName("/music/a.m4a", "audio/mp4")!!))
    }

    @Test
    fun onlyCodecExtensionsNameTheFormatOnTheirOwn() {
        assertTrue(extensionNamesFormat("/music/a.flac"))
        assertTrue(extensionNamesFormat("/music/a.mp3"))
        assertFalse("容器扩展名须另取 mime", extensionNamesFormat("/music/a.m4a"))
        assertFalse(extensionNamesFormat("/music/a.ogg"))
        assertFalse("无扩展名须另取 mime", extensionNamesFormat("/music/a"))
        assertFalse(extensionNamesFormat(null))
        assertFalse(extensionNamesFormat("/music/a."))
    }
}
