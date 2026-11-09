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
    fun `归一化剥离括号后缀与标点`() {
        // 平台给的版本后缀形态各异，括号内容一律剥离
        assertEquals("祝福祖国", TrackIdentity.normalize("祝福祖国 (Live)"))
        assertEquals("祝福祖国", TrackIdentity.normalize("祝福祖国（日文版）"))
        assertEquals("祝福祖国", TrackIdentity.normalize("祝福祖国 [Official]"))
        assertEquals("helloworld", TrackIdentity.normalize("Hello, World!"))
    }

    @Test
    fun `归一化转小写`() {
        assertEquals("valorant", TrackIdentity.normalize("VALORANT"))
    }

    // -----------------------------------------------------------------------
    // 歌手集合
    // -----------------------------------------------------------------------

    @Test
    fun `歌手集合切分所有连接符`() {
        assertEquals(setOf("郑浩", "冰洁"), TrackIdentity.artistSet("郑浩/冰洁"))
        assertEquals(setOf("郑浩", "冰洁"), TrackIdentity.artistSet("郑浩&冰洁"))
        assertEquals(setOf("郑浩", "冰洁"), TrackIdentity.artistSet("郑浩、冰洁"))
        assertEquals(setOf("郑浩", "冰洁"), TrackIdentity.artistSet("郑浩|冰洁"))
    }

    @Test
    fun `歌手集合不区分大小写切分合作标识`() {
        assertEquals(setOf("黄静美", "亦瑶"), TrackIdentity.artistSet("黄静美 feat. 亦瑶"))
        assertEquals(setOf("黄静美", "亦瑶"), TrackIdentity.artistSet("黄静美 ft 亦瑶"))
        assertEquals(setOf("黄静美", "亦瑶"), TrackIdentity.artistSet("黄静美 Feat 亦瑶"))
        assertEquals(setOf("黄静美", "亦瑶"), TrackIdentity.artistSet("黄静美 with 亦瑶"))
    }

    @Test
    fun `歌手集合丢弃空项`() {
        assertEquals(setOf("黄静美"), TrackIdentity.artistSet("黄静美"))
        assertTrue(TrackIdentity.artistSet("   ").isEmpty())
    }

    // -----------------------------------------------------------------------
    // 判同
    // -----------------------------------------------------------------------

    @Test
    fun `同名且歌手有交集视为同一曲目`() {
        assertTrue(TrackIdentity.matches("祝福祖国", "甲", "祝福祖国 (Live)", "甲"))
        // 合作歌手有增删时仍判同：歌手集合有交集即可
        assertTrue(TrackIdentity.matches("晴天", "黄静美", "晴天", "黄静美&亦瑶"))
    }

    @Test
    fun `同名但歌手不同视为不同曲目`() {
        // 只按标题判同会把同名翻唱误并，歌手无交集一律判为不同曲
        assertFalse(TrackIdentity.matches("祝福祖国", "甲", "祝福祖国", "乙"))
    }

    @Test
    fun `缺标题或歌手时不参与匹配`() {
        // 歌手缺失时宁漏不误并
        assertFalse(TrackIdentity.matches("祝福祖国", "", "祝福祖国", "甲"))
        assertFalse(TrackIdentity.matches("", "甲", "", "甲"))
        assertFalse(TrackIdentity.matches("祝福祖国", "甲", "祝福祖国", ""))
    }

    // -----------------------------------------------------------------------
    // 去重
    // -----------------------------------------------------------------------

    @Test
    fun `按曲目去重保留每份标识的首个`() {
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
    fun `按曲目去重跳过无标题项`() {
        val items = listOf("甲" to "", "乙" to "祝福祖国")
        assertEquals(listOf("乙" to "祝福祖国"), items.distinctByTrack(artist = { it.first }, title = { it.second }))
    }

    // -----------------------------------------------------------------------
    // 曲库索引
    // -----------------------------------------------------------------------

    @Test
    fun `已拥有索引命中精确键与模糊标识`() {
        val index = OwnedTrackIndex(listOf(track(1, "祝福祖国", "甲")))
        assertTrue(index.contains("祝福祖国", "甲"))
        assertTrue(index.contains("祝福祖国 (Live)", "甲"))
        assertFalse(index.contains("祝福祖国", "乙"))
        assertFalse(index.contains("其它歌", "甲"))
    }

    @Test
    fun `已拥有索引拒绝空标题`() {
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
