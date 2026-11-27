package com.yichao.evilgodxu.data.music.panel

import com.yichao.evilgodxu.data.music.api.stableIdFromString
import com.yichao.evilgodxu.data.music.model.MusicSearchSource
import com.yichao.evilgodxu.data.music.model.NeteaseSongSearchResult
import org.junit.Assert.assertEquals
import org.junit.Test

/** 同 id 候选须收敛，避免列表 key 重复崩溃 */
class SearchCandidateMergeTest {

    // 同 hash 多条记录，id 相同仅歌手不同
    private fun kugou(hash: String, artist: String): NeteaseSongSearchResult = NeteaseSongSearchResult(
        id = stableIdFromString(hash),
        title = "On The Edge of Destiny (英文版)",
        artist = artist,
        coverUrl = null,
        source = MusicSearchSource.KUGOU,
        sourceId = hash,
    )

    @Test
    fun `两次查询命中同一哈希时保留首个`() {
        val hash = "33B4300263D4499A08B6B83C0E718286"
        val withArtist = kugou(hash, "Wønder、Etherous Games")
        val artistOnly = kugou(hash, "Wønder")

        // 前置条件：两条记录同一身份
        assertEquals(withArtist.id, artistOnly.id)

        val merged = mergeCandidates(listOf(withArtist), listOf(artistOnly))
        assertEquals(1, merged.size)
        assertEquals(withArtist, merged.first())
    }

    @Test
    fun `单次查询内的重复被合并`() {
        val hash = "33B4300263D4499A08B6B83C0E718286"
        val merged = mergeCandidates(listOf(kugou(hash, "Wønder、Etherous Games"), kugou(hash, "Wønder")), emptyList())
        assertEquals(1, merged.size)
    }

    @Test
    fun `不同哈希按序全部保留`() {
        val english = kugou("33B4300263D4499A08B6B83C0E718286", "Wønder、Etherous Games")
        val rock = kugou("FA92F465335FC0C20DBC48705250F1D0", "Skar、Etherous Games")
        val chinese = kugou("1BDDD9D48297FF9A74EC28834440C206", "茶理理理子")

        assertEquals(listOf(english, rock, chinese), mergeCandidates(listOf(english, rock), listOf(chinese)))
    }

    @Test
    fun `不同来源的同一 ID 仍保留`() {
        // 不同平台 id 空间独立，撞号不同条目
        val id = stableIdFromString("33B4300263D4499A08B6B83C0E718286")
        val kugou = kugou("33B4300263D4499A08B6B83C0E718286", "Wønder")
        val other = kugou.copy(source = MusicSearchSource.KUWO, sourceId = "1")

        assertEquals(id, other.id)
        assertEquals(2, mergeCandidates(listOf(kugou), listOf(other)).size)
    }
}
