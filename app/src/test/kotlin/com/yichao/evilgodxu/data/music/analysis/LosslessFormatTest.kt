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
    fun containerNamesAreRecognizedCaseInsensitively() {
        assertTrue(isLosslessFormatName("flac"))
        assertTrue(isLosslessFormatName("FLAC"))
        assertTrue(isLosslessFormatName("AppleLossless"))
        assertTrue(isLosslessFormatName("dsf"))
        assertTrue(isLosslessFormatName("aiff"))
    }

    @Test
    fun lossyNamesAreRejected() {
        assertFalse(isLosslessFormatName("mp3"))
        assertFalse(isLosslessFormatName("aac"))
        assertFalse(isLosslessFormatName("ogg"))
        assertFalse(isLosslessFormatName("opus"))
    }

    @Test
    fun unknownNameIsRejected() {
        // 格式未知时不视为无损，避免把未知项展示成真实信息
        assertFalse(isLosslessFormatName(""))
        assertFalse(isLosslessFormatName("audio/mpeg"))
    }

    @Test
    fun signalPathFormatStripsMimePrefix() {
        assertTrue(isLosslessFormat(format("audio/flac")))
        assertTrue(isLosslessFormat(format("audio/x-lossless")))
        assertFalse(isLosslessFormat(format("audio/mpeg")))
    }

    @Test
    fun missingFormatIsNotLossless() {
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
