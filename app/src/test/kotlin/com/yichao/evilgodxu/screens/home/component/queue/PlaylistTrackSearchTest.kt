package com.yichao.evilgodxu.screens.home.component.queue

import com.yichao.evilgodxu.data.music.model.LyricLine
import com.yichao.evilgodxu.data.music.model.MusicTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 播放列表搜索的歌词维度复核。
 *
 * 关注两点：命中的排序（标题/歌手直接命中先于歌词命中，歌词命中按命中行数降序且稳定），
 * 以及歌词命中项取到的片段（首个非空命中行，原文优先、退到翻译）。
 */
class PlaylistTrackSearchTest {

    private fun track(
        id: Long,
        title: String,
        artist: String,
        lyrics: List<String> = emptyList(),
        translations: List<String?> = emptyList(),
    ) = MusicTrack(
        id = id,
        path = "/music/$id.mp3",
        audioUri = "file:///music/$id.mp3",
        title = title,
        artist = artist,
        duration = 0L,
        albumId = 0L,
        lyricLines = lyrics.mapIndexed { i, text ->
            LyricLine(timeMs = i * 1000L, text = text, translation = translations.getOrNull(i))
        },
    )

    @Test
    fun blankQueryReturnsEveryTrackWithoutSnippet() {
        val tracks = listOf(track(1, "Alpha", "A"), track(2, "Beta", "B"))

        val hits = searchPlaylistTracks(tracks, "  ")

        assertEquals(listOf(0, 1), hits.map { it.index })
        hits.forEach { assertNull(it.lyricSnippet) }
    }

    @Test
    fun titleAndArtistHitsRankBeforeLyricHits() {
        val tracks = listOf(
            track(1, "夜空中最亮的星", "逃跑计划"),
            track(2, "夏日", "某人", lyrics = listOf("我祈祷拥有一颗透明的心灵")),
        )

        val hits = searchPlaylistTracks(tracks, "夜空中最亮的星")

        assertEquals(listOf(0), hits.map { it.index })
        assertNull(hits.first().lyricSnippet)

        val lyricHits = searchPlaylistTracks(tracks, "祈祷")
        assertEquals(listOf(1), lyricHits.map { it.index })
        assertEquals("我祈祷拥有一颗透明的心灵", lyricHits.first().lyricSnippet)
    }

    @Test
    fun lyricHitsAreOrderedByMatchCountDescending() {
        val tracks = listOf(
            track(1, "A", "x", lyrics = listOf("风")),
            track(2, "B", "y", lyrics = listOf("风", "风继续吹", "风再起时")),
            track(3, "C", "z", lyrics = listOf("风", "风雨")),
        )

        val hits = searchPlaylistTracks(tracks, "风")

        assertEquals(listOf(1, 2, 0), hits.map { it.index })
    }

    @Test
    fun equalMatchCountKeepsPlaylistOrder() {
        val tracks = listOf(
            track(1, "A", "x", lyrics = listOf("一样的风声")),
            track(2, "B", "y", lyrics = listOf("风也温柔")),
        )

        assertEquals(listOf(0, 1), searchPlaylistTracks(tracks, "风").map { it.index })
    }

    @Test
    fun snippetFallsBackToTranslationWhenOnlyTranslationMatches() {
        val tracks = listOf(
            track(1, "Song", "Artist", lyrics = listOf("夜明け"), translations = listOf("黎明")),
        )

        val hits = searchPlaylistTracks(tracks, "黎明")

        assertEquals(1, hits.size)
        assertEquals("黎明", hits.first().lyricSnippet)
    }

    @Test
    fun tracksWithoutMatchAreExcluded() {
        val tracks = listOf(
            track(1, "A", "x", lyrics = listOf("无关歌词")),
            track(2, "B", "y"),
        )

        assertEquals(emptyList<Int>(), searchPlaylistTracks(tracks, "不存在的词").map { it.index })
    }
}