package com.yichao.evilgodxu.data.music.analysis

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 容器头读取门限：哪些扩展名值得开文件读一个窗口。
 *
 * 门限必须与「格式名怎么定」对得上——格式名说不出码流的那一族（容器扩展名）里，凡是能装下无损
 * 内容的都要在这里放行，否则容器头那条正面认定路径形同虚设：一首 ALAC 会被按容器名判成有损，
 * 随后被提示做无损升级。
 */
class TrackAudioInfoReaderTest {

    @Test
    fun isoBmffContainersThatCanHoldAlacAreRead() {
        assertTrue(TrackAudioInfoReader.isContainerHeaderFormat("/music/a.m4a"))
        assertTrue(TrackAudioInfoReader.isContainerHeaderFormat("/music/a.mp4"))
        // 有声书的 .m4b 同样可能装 ALAC
        assertTrue(TrackAudioInfoReader.isContainerHeaderFormat("/music/a.m4b"))
        assertTrue(TrackAudioInfoReader.isContainerHeaderFormat("/music/A.M4B"))
    }

    @Test
    fun losslessAndPcmContainersAreRead() {
        assertTrue(TrackAudioInfoReader.isContainerHeaderFormat("/music/a.flac"))
        assertTrue(TrackAudioInfoReader.isContainerHeaderFormat("/music/a.wav"))
    }

    @Test
    fun formatsWithoutPlatformExtractorAreNotRead() {
        // DSD（.dsf/.dff）与 APE（.ape）无媒体提取器、无从播放，相关支持已整体移除，
        // 故不再为它们开文件读容器头
        assertFalse(TrackAudioInfoReader.isContainerHeaderFormat("/music/a.dsf"))
        assertFalse(TrackAudioInfoReader.isContainerHeaderFormat("/music/a.dff"))
        assertFalse(TrackAudioInfoReader.isContainerHeaderFormat("/music/a.ape"))
    }

    @Test
    fun lossyFormatsAreNotRead() {
        // 有损格式的容器头给不出位深与码流名，读它只是白开一次文件；
        // .m4p/.m4r 内容恒为有损，同理不读，格式名交给 mime 那条路
        assertFalse(TrackAudioInfoReader.isContainerHeaderFormat("/music/a.mp3"))
        assertFalse(TrackAudioInfoReader.isContainerHeaderFormat("/music/a.ogg"))
        assertFalse(TrackAudioInfoReader.isContainerHeaderFormat("/music/a.m4p"))
        assertFalse(TrackAudioInfoReader.isContainerHeaderFormat("/music/a.m4r"))
        assertFalse(TrackAudioInfoReader.isContainerHeaderFormat("/music/a.mka"))
    }

    @Test
    fun missingExtensionIsNotRead() {
        assertFalse(TrackAudioInfoReader.isContainerHeaderFormat(null))
        assertFalse(TrackAudioInfoReader.isContainerHeaderFormat(""))
        assertFalse(TrackAudioInfoReader.isContainerHeaderFormat("/music/a"))
        assertFalse(TrackAudioInfoReader.isContainerHeaderFormat("/music/a."))
    }
}
