package com.yichao.evilgodxu.data.music.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 音轨身份判同复核。
 *
 * 各平台对同一首歌的写法不一致（版本后缀、合作歌手连接符、歌手译名），
 * 判同过松会把同名不同曲并成一条，过紧则同一首歌在推荐与去重中裂成多条。
 */
class TrackIdentityTest {

    // -----------------------------------------------------------------------
    // 归一化
    // -----------------------------------------------------------------------

    @Test
    fun normalizeStripsBracketSuffixesAndPunctuation() {
        // 平台给的版本后缀形态各异，括号内容一律剥离
        assertEquals("祝福祖国", TrackIdentity.normalize("祝福祖国 (Live)"))
        assertEquals("祝福祖国", TrackIdentity.normalize("祝福祖国（日文版）"))
        assertEquals("祝福祖国", TrackIdentity.normalize("祝福祖国 [Official]"))
        assertEquals("helloworld", TrackIdentity.normalize("Hello, World!"))
    }

    @Test
    fun normalizeLowercases() {
        assertEquals("valorant", TrackIdentity.normalize("VALORANT"))
    }

    // -----------------------------------------------------------------------
    // 歌手集合
    // -----------------------------------------------------------------------

    @Test
    fun artistSetSplitsEveryConnector() {
        assertEquals(setOf("郑浩", "冰洁"), TrackIdentity.artistSet("郑浩/冰洁"))
        assertEquals(setOf("郑浩", "冰洁"), TrackIdentity.artistSet("郑浩&冰洁"))
        assertEquals(setOf("郑浩", "冰洁"), TrackIdentity.artistSet("郑浩、冰洁"))
        assertEquals(setOf("郑浩", "冰洁"), TrackIdentity.artistSet("郑浩|冰洁"))
    }

    @Test
    fun artistSetSplitsFeatConnectorsCaseInsensitively() {
        assertEquals(setOf("黄静美", "亦瑶"), TrackIdentity.artistSet("黄静美 feat. 亦瑶"))
        assertEquals(setOf("黄静美", "亦瑶"), TrackIdentity.artistSet("黄静美 ft 亦瑶"))
        assertEquals(setOf("黄静美", "亦瑶"), TrackIdentity.artistSet("黄静美 Feat 亦瑶"))
        assertEquals(setOf("黄静美", "亦瑶"), TrackIdentity.artistSet("黄静美 with 亦瑶"))
    }

    @Test
    fun artistSetDropsEmptyEntries() {
        assertEquals(setOf("黄静美"), TrackIdentity.artistSet("黄静美"))
        assertTrue(TrackIdentity.artistSet("   ").isEmpty())
    }

    // -----------------------------------------------------------------------
    // 判同
    // -----------------------------------------------------------------------

    @Test
    fun sameTitleWithOverlappingArtistIsSameTrack() {
        assertTrue(TrackIdentity.matches("祝福祖国", "甲", "祝福祖国 (Live)", "甲"))
        // 合作歌手有增删时仍判同：歌手集合有交集即可
        assertTrue(TrackIdentity.matches("晴天", "黄静美", "晴天", "黄静美&亦瑶"))
    }

    @Test
    fun sameTitleWithDifferentArtistIsNotSameTrack() {
        // 只按标题判同会把同名翻唱误并，歌手无交集一律判为不同曲
        assertFalse(TrackIdentity.matches("祝福祖国", "甲", "祝福祖国", "乙"))
    }

    @Test
    fun missingTitleOrArtistIsNeverAMatch() {
        // 歌手缺失时宁漏不误并
        assertFalse(TrackIdentity.matches("祝福祖国", "", "祝福祖国", "甲"))
        assertFalse(TrackIdentity.matches("", "甲", "", "甲"))
        assertFalse(TrackIdentity.matches("祝福祖国", "甲", "祝福祖国", ""))
    }

    // -----------------------------------------------------------------------
    // 去重
    // -----------------------------------------------------------------------

    @Test
    fun distinctByTrackKeepsFirstOfEachIdentity() {
        val items = listOf(
            "甲" to "祝福祖国",
            "乙" to "祝福祖国",
            "甲" to "祝福祖国(Live)",
        )
        // 第 1、3 条判同（歌手有交集），第 2 条是另一个歌手的版本
        val kept = items.distinctByTrack(artist = { it.first }, title = { it.second })
        assertEquals(listOf("甲" to "祝福祖国", "乙" to "祝福祖国"), kept)
    }

    @Test
    fun distinctByTrackSkipsEntriesWithoutTitle() {
        val items = listOf("甲" to "", "乙" to "祝福祖国")
        assertEquals(listOf("乙" to "祝福祖国"), items.distinctByTrack(artist = { it.first }, title = { it.second }))
    }

    // -----------------------------------------------------------------------
    // 曲库索引
    // -----------------------------------------------------------------------

    @Test
    fun ownedIndexHitsExactKeyAndFuzzyIdentity() {
        val index = OwnedTrackIndex(listOf(track(1, "祝福祖国", "甲")))
        assertTrue(index.contains("祝福祖国", "甲"))
        assertTrue(index.contains("祝福祖国 (Live)", "甲"))
        assertFalse(index.contains("祝福祖国", "乙"))
        assertFalse(index.contains("其它歌", "甲"))
    }

    @Test
    fun ownedIndexRejectsEmptyTitle() {
        val index = OwnedTrackIndex(listOf(track(1, "祝福祖国", "甲")))
        assertFalse(index.contains("", "甲"))
    }

    private fun track(id: Long, title: String, artist: String): MusicTrack = MusicTrack(
        id = id,
        path = "/music/$id.flac",
        audioUri = "file:///music/$id.flac",
        title = title,
        artist = artist,
        duration = 200_000L,
        albumId = id,
    )
}
