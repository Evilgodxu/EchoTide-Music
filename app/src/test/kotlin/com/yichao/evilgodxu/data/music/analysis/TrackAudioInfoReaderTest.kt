package com.yichao.evilgodxu.data.music.analysis

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 哪些扩展名值得开文件读容器头 */
class TrackAudioInfoReaderTest {

    @Test
    fun `能容纳 ALAC 的 ISO BMFF 容器会被读取`() {
        assertTrue(TrackAudioInfoReader.isContainerHeaderFormat("/music/a.m4a"))
        assertTrue(TrackAudioInfoReader.isContainerHeaderFormat("/music/a.mp4"))
        // 有声书的 .m4b 同样可能装 ALAC
        assertTrue(TrackAudioInfoReader.isContainerHeaderFormat("/music/a.m4b"))
        assertTrue(TrackAudioInfoReader.isContainerHeaderFormat("/music/A.M4B"))
    }

    @Test
    fun `无损与 PCM 容器会被读取`() {
        assertTrue(TrackAudioInfoReader.isContainerHeaderFormat("/music/a.flac"))
        assertTrue(TrackAudioInfoReader.isContainerHeaderFormat("/music/a.wav"))
    }

    @Test
    fun `平台无解封装器的格式不读取`() {
        // DSD/APE 无解封装器，不读容器头
        assertFalse(TrackAudioInfoReader.isContainerHeaderFormat("/music/a.dsf"))
        assertFalse(TrackAudioInfoReader.isContainerHeaderFormat("/music/a.dff"))
        assertFalse(TrackAudioInfoReader.isContainerHeaderFormat("/music/a.ape"))
    }

    @Test
    fun `有损格式不读取`() {
        // 有损格式读头无收益，m4p/m4r 同理
        assertFalse(TrackAudioInfoReader.isContainerHeaderFormat("/music/a.mp3"))
        assertFalse(TrackAudioInfoReader.isContainerHeaderFormat("/music/a.ogg"))
        assertFalse(TrackAudioInfoReader.isContainerHeaderFormat("/music/a.m4p"))
        assertFalse(TrackAudioInfoReader.isContainerHeaderFormat("/music/a.m4r"))
        assertFalse(TrackAudioInfoReader.isContainerHeaderFormat("/music/a.mka"))
    }

    @Test
    fun `缺扩展名时不读取`() {
        assertFalse(TrackAudioInfoReader.isContainerHeaderFormat(null))
        assertFalse(TrackAudioInfoReader.isContainerHeaderFormat(""))
        assertFalse(TrackAudioInfoReader.isContainerHeaderFormat("/music/a"))
        assertFalse(TrackAudioInfoReader.isContainerHeaderFormat("/music/a."))
    }
}
