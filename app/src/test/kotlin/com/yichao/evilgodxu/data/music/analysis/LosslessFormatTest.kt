package com.yichao.evilgodxu.data.music.analysis

import com.yichao.evilgodxu.data.music.playback.AudioSignalPathFormat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 无损格式判定复核：决定一首歌是否还值得升级。
 *
 * 判据宁紧勿松 —— 未知格式一律不算无损，否则升级入口会对已无损的曲目反复提示。
 */
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
        // 格式未知时不视为无损，避免把未知项展示成真实信息
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
