package com.yichao.evilgodxu.data.music.api

import com.yichao.evilgodxu.data.music.model.MusicSearchSource
import com.yichao.evilgodxu.data.music.model.NeteaseSongSearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 详情补全的字段合并：详情与搜索结果各缺一部分，逐项回退。
 *
 * 合并只动标签字段：曲目标题、歌手、时长来自搜索结果，详情里同名项不得覆盖它们，
 * 否则缓存文件名（由搜索结果拼出）会与文件内标签对不上。
 */
class TrackDetailMergeTest {

    private val base = NeteaseSongSearchResult(
        id = 186016L,
        title = "晴天",
        artist = "周杰伦",
        coverUrl = "https://p1.music.126.net/base.jpg",
        duration = 269_000L,
        source = MusicSearchSource.NETEASE,
        album = "叶惠美",
    )

    private val detail = NeteaseSongSearchResult(
        id = 186016L,
        title = "晴天 (Live)",
        artist = "周杰伦 / 某嘉宾",
        coverUrl = "https://p1.music.126.net/detail.jpg",
        duration = 300_000L,
        source = MusicSearchSource.NETEASE,
        releaseDate = "2003-07-31",
        trackNumber = 3,
        discNumber = null,
        copyright = "杰威尔",
    )

    @Test
    fun `详情补齐搜索缺失的标签字段`() {
        val merged = mergeTagFields(base, detail)

        assertEquals("叶惠美", merged.album)
        assertEquals("2003-07-31", merged.releaseDate)
        assertEquals(3, merged.trackNumber)
        assertEquals("杰威尔", merged.copyright)
    }

    @Test
    fun `详情缺失的字段保留搜索结果的值`() {
        val merged = mergeTagFields(base, detail)

        assertNull(merged.discNumber)
        assertEquals("叶惠美", merged.album)
    }

    @Test
    fun `曲目身份与展示字段不被详情覆盖`() {
        val merged = mergeTagFields(base, detail)

        assertEquals("晴天", merged.title)
        assertEquals("周杰伦", merged.artist)
        assertEquals(269_000L, merged.duration)
        assertEquals("https://p1.music.126.net/base.jpg", merged.coverUrl)
    }

    @Test
    fun `详情为空时搜索结果原样保留`() {
        val empty = NeteaseSongSearchResult(
            id = 186016L,
            title = "晴天",
            artist = "周杰伦",
            coverUrl = null,
        )
        val merged = mergeTagFields(base, empty)

        assertEquals("叶惠美", merged.album)
        assertNull(merged.releaseDate)
        assertEquals(base, merged)
    }
}
