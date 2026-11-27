package com.yichao.evilgodxu.data.music.metadata

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** 头两页 granule 为 0，末页置 EOS */
class OpusPageLayoutTest {

    @Test
    fun `重写后头页粒度仍为零`() {
        val source = File(FIXTURE_DIR + "sample.opus").readBytes()
        val sourcePages = AudioMetaProbe.oggPages(source)
        assertEquals("样本首页未标记 BOS", 2, sourcePages.first().headerType and 2)
        assertEquals("样本注释页 granule 非 0", 0L, sourcePages[1].granule)

        val rewritten = MusicMetadataWriter.writeMetadataBytes(source, "标题", "艺术家", "专辑", COVER, LYRICS)!!
        val pages = AudioMetaProbe.oggPages(rewritten)
        assertEquals("改写后首页未标记 BOS", 2, pages.first().headerType and 2)
        assertEquals("改写后末页未标记 EOS", 4, pages.last().headerType and 4)
        assertEquals("页序号不连续", pages.indices.toList(), pages.map { it.sequence - pages.first().sequence })
        assertTrue("改写后页 CRC 无效", pages.all { it.crcValid })
        assertEquals("改写后头页 granule 非 0", 0L, pages[0].granule)
        assertEquals("改写后注释页 granule 非 0", 0L, pages[1].granule)
        assertTrue("音频 granule 丢失", pages.last().granule == sourcePages.last().granule)
    }

    private companion object {
        const val FIXTURE_DIR = "src/test/resources/audio/"
        const val LYRICS = "[00:01.00]第一行\n[00:03.50]第二行"
        val COVER = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(60) { it.toByte() }
    }
}

/** 全容器往返：写入、独立解析、应用读取器复核 */
@RunWith(Parameterized::class)
class FormatMetadataRoundTripTest(private val fixture: String) {

    @Test
    fun `元数据在各类容器间往返一致`() {
        val source = fixtureFile(fixture).readBytes()

        // 首轮：无标签样本建立标签
        val first = MusicMetadataWriter.writeMetadataBytes(source, TITLE, ARTIST, ALBUM, COVER, LYRICS)
        assertNotNull("$fixture 未能写入元数据", first)
        assertMeta(first!!, TITLE, ARTIST, ALBUM)
        assertArrayEquals("$fixture 音频区被改动", AudioMetaProbe.audioRegion(source), AudioMetaProbe.audioRegion(first))
        assertEquals("$fixture 内嵌歌词读不回", LYRICS, appLyrics(first))
        appCover(first)?.let { assertArrayEquals("$fixture 内嵌封面读不回", COVER, it) }

        // 次轮：只改标题，其余字段应原样保留
        val second = MusicMetadataWriter.writeMetadataBytes(first, TITLE_UPDATED, null, null, null, null)
        assertNotNull("$fixture 二次写入失败", second)
        assertEquals("$fixture 标题未更新", TITLE_UPDATED, AudioMetaProbe.read(second!!).title)
        val meta = AudioMetaProbe.read(second)
        assertEquals("$fixture 未写入的艺术家丢失", ARTIST, meta.artist)
        assertEquals("$fixture 未写入的专辑丢失", ALBUM, meta.album)
        assertEquals("$fixture 未写入的歌词丢失", LYRICS, meta.lyrics)
        assertArrayEquals("$fixture 未写入的封面丢失", COVER, meta.cover)
        assertArrayEquals("$fixture 二次写入改动了音频区", AudioMetaProbe.audioRegion(source), AudioMetaProbe.audioRegion(second))
        // 覆盖写入不得残留旧标题
        assertNull("$fixture 旧标题残留", AudioMetaProbe.read(second).title?.takeIf { it == TITLE })
    }

    private fun assertMeta(bytes: ByteArray, title: String, artist: String, album: String) {
        val meta = AudioMetaProbe.read(bytes)
        assertEquals("$fixture 标题不一致", title, meta.title)
        assertEquals("$fixture 艺术家不一致", artist, meta.artist)
        assertEquals("$fixture 专辑不一致", album, meta.album)
        assertEquals("$fixture 歌词不一致", LYRICS, meta.lyrics)
        assertArrayEquals("$fixture 封面不一致", COVER, meta.cover)
    }

    // 内嵌歌词：MP3 走 USLT，余走注释
    private fun appLyrics(bytes: ByteArray): String? = when {
        fixture.endsWith(".mp3") -> Id3v2Tag.readUslt(bytes, 0)
        fixture.endsWith(".flac") || fixture.endsWith(".opus") || fixture.endsWith(".m4a") ||
            fixture.endsWith(".ogg") -> MusicEmbeddedLyricReader.extractLyrics(bytes)
        else -> LosslessContainerTags.readLyrics(bytes, bytes, 0L, null)
    }

    // FLAC/Ogg/M4A 封面由平台读取
    private fun appCover(bytes: ByteArray): ByteArray? = when {
        fixture.endsWith(".mp3") -> Id3v2Tag.readApic(bytes, 0)
        fixture.endsWith(".flac") || fixture.endsWith(".opus") || fixture.endsWith(".m4a") -> null
        else -> LosslessContainerTags.readCover(bytes, bytes, 0L, null)
    }

    private fun fixtureFile(name: String) = File("src/test/resources/audio/$name")

    private companion object {
        const val TITLE = "探针标题"
        const val TITLE_UPDATED = "探针标题（改）"
        const val ARTIST = "探针艺术家"
        const val ALBUM = "探针专辑"
        const val LYRICS = "[00:01.00]第一行\n[00:03.50]第二行"

        // 最小 JPEG，魔数嗅探 MIME
        val COVER = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(60) { it.toByte() }

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun fixtures(): List<String> = listOf(
            "sample.wav", "sample.flac", "sample.mp3",
            "sample.m4a", "sample.opus", "sample.ogg",
        )
    }
}
