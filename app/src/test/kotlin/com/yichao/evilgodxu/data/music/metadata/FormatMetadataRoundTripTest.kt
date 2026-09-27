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

/**
 * Ogg Opus 改写后的页面结构：Ogg Opus 要求承载 OpusHead 与 OpusTags 的前两页 granule 为 0，
 * 末页置 EOS。标签包长度变化后若仍按原页面段数重新分页，音频包会被吸进注释页，
 * 注释页 granule 随之变为音频位置，改写结果随即无法被解码器打开。
 */
class OpusPageLayoutTest {

    @Test
    fun rewriteKeepsHeaderPageGranuleZero() {
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

/**
 * 支持元数据写入的全部容器的实测往返：样本取自 src/test/resources/audio（本地生成，
 * 用标准编码器产出或按容器规范构造），逐个走「写入 → 独立解析 → 应用读取器复核 → 覆盖写入」。
 *
 * 断言分三层，互相独立：
 *   1. 独立解析器 [AudioMetaProbe] 按容器规范读回五个字段，核对写入结果本身；
 *   2. 应用自身的读取器（内嵌歌词/封面）复核，保证显示路径能读到；
 *   3. 音频区字节前后一致，保证改写只动元数据。
 */
@RunWith(Parameterized::class)
class FormatMetadataRoundTripTest(private val fixture: String) {

    @Test
    fun metadataRoundTripsAcrossContainers() {
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

    // 应用自身的内嵌歌词读取器：MP3 走 ID3 USLT，FLAC/OGG/M4A 走容器注释，其余走容器标签层
    private fun appLyrics(bytes: ByteArray): String? = when {
        fixture.endsWith(".mp3") -> Id3v2Tag.readUslt(bytes, 0)
        fixture.endsWith(".flac") || fixture.endsWith(".opus") || fixture.endsWith(".m4a") ->
            MusicEmbeddedLyricReader.extractLyrics(bytes)
        else -> LosslessContainerTags.readLyrics(bytes, bytes, 0L, null)
    }

    // 应用自身的封面读取器：FLAC/Ogg/M4A 的封面由平台读取，自实现不覆盖
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

        // 最小 JPEG 头 + 载荷：写入端按魔数嗅探 MIME，读取端按字节比对
        val COVER = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(60) { it.toByte() }

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun fixtures(): List<String> = listOf(
            "sample.wav", "sample.aiff", "sample.aifc", "sample.flac", "sample.mp3",
            "sample.m4a", "sample.opus", "sample.dsf", "sample.dff", "sample.ape",
        )
    }
}
