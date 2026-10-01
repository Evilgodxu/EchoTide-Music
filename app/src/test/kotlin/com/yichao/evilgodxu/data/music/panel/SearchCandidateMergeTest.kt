package com.yichao.evilgodxu.data.music.panel

import com.yichao.evilgodxu.data.music.api.stableIdFromString
import com.yichao.evilgodxu.data.music.model.MusicSearchSource
import com.yichao.evilgodxu.data.music.model.NeteaseSongSearchResult
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 歌词与封面候选聚合的去重复核。
 *
 * 候选列表以 id 作列表 key，同一平台对同一歌曲返回的多条记录（同 id）必须收敛为一条，
 * 否则列表 key 重复会在渲染时抛 IllegalArgumentException 崩溃。
 */
class SearchCandidateMergeTest {

    // 酷狗把同一文件 hash 按不同演绎者各列一条：hash 与 id 相同，仅歌手展示不同
    private fun kugou(hash: String, artist: String): NeteaseSongSearchResult = NeteaseSongSearchResult(
        id = stableIdFromString(hash),
        title = "On The Edge of Destiny (英文版)",
        artist = artist,
        coverUrl = null,
        source = MusicSearchSource.KUGOU,
        sourceId = hash,
    )

    @Test
    fun sameHashFromBothQueriesKeepsFirstOccurrence() {
        val hash = "33B4300263D4499A08B6B83C0E718286"
        val withArtist = kugou(hash, "Wønder、Etherous Games")
        val artistOnly = kugou(hash, "Wønder")

        // 前置条件：平台返回的两条记录确实是同一个身份
        assertEquals(withArtist.id, artistOnly.id)

        val merged = mergeCandidates(listOf(withArtist), listOf(artistOnly))
        assertEquals(1, merged.size)
        assertEquals(withArtist, merged.first())
    }

    @Test
    fun duplicateInsideSingleQueryIsCollapsed() {
        val hash = "33B4300263D4499A08B6B83C0E718286"
        val merged = mergeCandidates(listOf(kugou(hash, "Wønder、Etherous Games"), kugou(hash, "Wønder")), emptyList())
        assertEquals(1, merged.size)
    }

    @Test
    fun distinctHashesAreAllKeptInOrder() {
        val english = kugou("33B4300263D4499A08B6B83C0E718286", "Wønder、Etherous Games")
        val rock = kugou("FA92F465335FC0C20DBC48705250F1D0", "Skar、Etherous Games")
        val chinese = kugou("1BDDD9D48297FF9A74EC28834440C206", "茶理理理子")

        assertEquals(listOf(english, rock, chinese), mergeCandidates(listOf(english, rock), listOf(chinese)))
    }

    @Test
    fun sameIdFromDifferentSourcesIsKept() {
        // 不同平台的 id 空间相互独立，撞号不代表同一条目
        val id = stableIdFromString("33B4300263D4499A08B6B83C0E718286")
        val kugou = kugou("33B4300263D4499A08B6B83C0E718286", "Wønder")
        val other = kugou.copy(source = MusicSearchSource.KUWO, sourceId = "1")

        assertEquals(id, other.id)
        assertEquals(2, mergeCandidates(listOf(kugou), listOf(other)).size)
    }
}
