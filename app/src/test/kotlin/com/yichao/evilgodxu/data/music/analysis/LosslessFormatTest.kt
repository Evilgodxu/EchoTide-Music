package com.yichao.evilgodxu.data.music.analysis

import com.yichao.evilgodxu.data.music.playback.AudioSignalPathFormat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 无损判定宁紧勿松，未知不算无损 */
class LosslessFormatTest {

    @Test
    fun `容器名不区分大小写`() {
        assertTrue(isLosslessFormatName("flac"))
        assertTrue(isLosslessFormatName("FLAC"))
        assertTrue(isLosslessFormatName("AppleLossless"))
    }

    @Test
    fun `有损格式名被拒绝`() {
        assertFalse(isLosslessFormatName("mp3"))
        assertFalse(isLosslessFormatName("aac"))
        assertFalse(isLosslessFormatName("ogg"))
        assertFalse(isLosslessFormatName("opus"))
    }

    @Test
    fun `未知格式名被拒绝`() {
        // 未知格式不算无损
        assertFalse(isLosslessFormatName(""))
        assertFalse(isLosslessFormatName("audio/mpeg"))
    }

    @Test
    fun `信号路径格式剥离 MIME 前缀`() {
        assertTrue(isLosslessFormat(format("audio/flac")))
        assertTrue(isLosslessFormat(format("audio/x-lossless")))
        assertFalse(isLosslessFormat(format("audio/mpeg")))
    }

    @Test
    fun `缺格式名时不算无损`() {
        assertFalse(isLosslessFormat(format(null)))
    }

    private fun format(value: String?): AudioSignalPathFormat = AudioSignalPathFormat(
        format = value,
        sampleRate = null,
        outputRate = null,
        bitDepth = null,
        channels = null,
        bitrate = null,
    )
}
