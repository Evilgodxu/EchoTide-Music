package com.yichao.evilgodxu.data.music.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 扩展名定案优先，容器扩展名让位 mime */
class FormatDisplayNameTest {

    @Test
    fun `单一编码扩展名直接给出格式名`() {
        assertEquals("FLAC", formatDisplayName("/music/a.flac", "audio/flac"))
        assertEquals("MP3", formatDisplayName("/music/a.mp3", "audio/mpeg"))
        assertEquals("OPUS", formatDisplayName("/music/a.opus", null))
        assertEquals("WAV", formatDisplayName("/music/a.wav", null))
        // mpeg 展示名统一为 MP3
        assertEquals("MP3", formatDisplayName("/music/a.mpeg", null))
        assertEquals("FLAC", formatDisplayName("/music/a.FLAC", null))
    }

    @Test
    fun `容器类扩展名以 MIME 为准`() {
        // m4a 装 AAC/ALAC，扩展名不定
        assertEquals("ALAC", formatDisplayName("/music/a.m4a", "audio/alac"))
        assertEquals("AAC", formatDisplayName("/music/a.m4a", "audio/mp4"))
        assertEquals("OPUS", formatDisplayName("/music/a.ogg", "audio/opus"))
        assertEquals("FLAC", formatDisplayName("/music/a.mp4", "audio/flac"))
    }

    @Test
    fun `容器类扩展名回落到自身名称`() {
        // mime 定不出时保留容器名
        assertEquals("M4A", formatDisplayName("/music/a.m4a", null))
        assertEquals("OGG", formatDisplayName("/music/a.ogg", null))
    }

    @Test
    fun `缺扩展名时用 MIME`() {
        assertEquals("FLAC", formatDisplayName(null, "audio/flac"))
        assertEquals("MP3", formatDisplayName("/music/no-extension", "audio/mpeg"))
        assertEquals("ALAC", formatDisplayName("/music/no-extension", "audio/alac"))
        assertNull("两边都定不出时不产出条目", formatDisplayName(null, null))
    }

    @Test
    fun `容器名不表明无损时 ALAC 仍算无损`() {
        // 容器名认不出无损，格式名认得出
        assertTrue(isLosslessFormatName(formatDisplayName("/music/a.m4a", "audio/alac")!!))
        assertFalse(isLosslessFormatName(formatDisplayName("/music/a.m4a", "audio/mp4")!!))
    }

    @Test
    fun `只有编码类扩展名能独立给出格式名`() {
        assertTrue(extensionNamesFormat("/music/a.flac"))
        assertTrue(extensionNamesFormat("/music/a.mp3"))
        assertFalse("容器扩展名须另取 mime", extensionNamesFormat("/music/a.m4a"))
        assertFalse(extensionNamesFormat("/music/a.ogg"))
        assertFalse("无扩展名须另取 mime", extensionNamesFormat("/music/a"))
        assertFalse(extensionNamesFormat(null))
        assertFalse(extensionNamesFormat("/music/a."))
    }
}
