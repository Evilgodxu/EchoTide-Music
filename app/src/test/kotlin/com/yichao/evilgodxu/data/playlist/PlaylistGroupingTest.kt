package com.yichao.evilgodxu.data.playlist

import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.playback.PlaylistSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 歌单分组与来源解析复核。
 *
 * 来源 key 是持久化的唯一身份，解析必须与其生成规则（专辑/艺术家分组 key）保持同口径，
 * 否则曲库变化后同一 key 会解析出不同集合。
 */
class PlaylistGroupingTest {

    private val tracks = listOf(
        track(1, "一", artist = "甲", albumId = 10, albumName = "甲专辑"),
        track(2, "二", artist = "甲 / 乙", albumId = 20, albumName = "合辑"),
        track(3, "三", artist = "乙", albumId = 20, albumName = "合辑"),
    )

    // -----------------------------------------------------------------------
    // id 集合解析
    // -----------------------------------------------------------------------

    @Test
    fun `解析保持集合顺序并跳过未知 ID`() {
        // 集合顺序即播放顺序，不能按曲库顺序重排；已删除曲目的 id 静默跳过
        assertEquals(listOf(3L, 1L), resolveTracks(tracks, listOf(3, 99, 1)).map { it.id })
    }

    @Test
    fun `任一侧为空时解析结果为空`() {
        assertTrue(resolveTracks(tracks, emptyList()).isEmpty())
        assertTrue(resolveTracks(emptyList(), listOf(1L)).isEmpty())
    }

    @Test
    fun `最近曲目跟随最近 ID 顺序`() {
        assertEquals(listOf(2L, 1L), recentTracks(tracks, listOf(2, 1)).map { it.id })
    }

    @Test
    fun `智能歌单曲目数忽略缺失曲目`() {
        assertEquals(2, smartTrackCount(tracks, listOf(1, 2, 99)))
        assertEquals(0, smartTrackCount(tracks, emptyList()))
    }

    // -----------------------------------------------------------------------
    // 来源解析
    // -----------------------------------------------------------------------

    @Test
    fun `智能来源解析最近与收藏`() {
        assertEquals(listOf(3L, 1L), resolveSourceTracks(tracks, emptyList(), emptySet(), listOf(3, 1), source("smart:RECENT")).map { it.id })
        assertEquals(listOf(2L), resolveSourceTracks(tracks, emptyList(), setOf(2), emptyList(), source("smart:FAVORITE")).map { it.id })
    }

    @Test
    fun `自建来源解析歌单曲目 ID`() {
        val playlists = listOf(Playlist(7, "我的", listOf(3, 1), 0L))
        assertEquals(
            listOf(3L, 1L),
            resolveSourceTracks(tracks, playlists, emptySet(), emptyList(), source("custom:7")).map { it.id },
        )
    }

    @Test
    fun `专辑来源按专辑 ID 解析`() {
        assertEquals(listOf(2L, 3L), resolveSourceTracks(tracks, emptyList(), emptySet(), emptyList(), source("album:20")).map { it.id })
    }

    @Test
    fun `歌手来源解析列出的每位歌手`() {
        // 多歌手曲目「甲 / 乙」在两位歌手名下都应出现
        assertEquals(listOf(1L, 2L), resolveSourceTracks(tracks, emptyList(), emptySet(), emptyList(), source("artist:甲")).map { it.id })
        assertEquals(listOf(2L, 3L), resolveSourceTracks(tracks, emptyList(), emptySet(), emptyList(), source("artist:乙")).map { it.id })
    }

    @Test
    fun `未知来源解析不出内容`() {
        assertTrue(resolveSourceTracks(tracks, emptyList(), emptySet(), emptyList(), source("whatever")).isEmpty())
    }

    // -----------------------------------------------------------------------
    // 来源有效性
    // -----------------------------------------------------------------------

    @Test
    fun `智能来源始终有效`() {
        assertTrue(isViewSourceValid(emptyList(), emptyList(), source("smart:RECENT")))
    }

    @Test
    fun `自建来源需要已存在的歌单`() {
        val playlists = listOf(Playlist(7, "我的", emptyList(), 0L))
        // 空歌单仍是有效状态，判据是歌单是否存在而非是否为空
        assertTrue(isViewSourceValid(playlists, emptyList(), source("custom:7")))
        assertFalse(isViewSourceValid(playlists, emptyList(), source("custom:8")))
    }

    @Test
    fun `专辑与歌手来源需要有可解析的曲目`() {
        assertTrue(isViewSourceValid(emptyList(), tracks, source("album:20")))
        assertFalse(isViewSourceValid(emptyList(), emptyList(), source("album:20")))
    }

    // -----------------------------------------------------------------------
    // 分组
    // -----------------------------------------------------------------------

    @Test
    fun `专辑分组按 ID 归键、以首曲命名`() {
        val groups = albumGroups(tracks, "未知专辑")
        assertEquals(listOf("合辑", "甲专辑"), groups.map { it.name })
        assertEquals(listOf("album:20", "album:10"), groups.map { it.key })
        // 按 albumId 分组而非按名字：同名不同专辑不会被并入一块
        assertEquals(listOf(2L, 3L), groups[0].trackIds)
        assertEquals(listOf(1L), groups[1].trackIds)
    }

    @Test
    fun `专辑名为空时分组回落到未知`() {
        val groups = albumGroups(listOf(track(1, "一", albumId = 10, albumName = " ")), "未知专辑")
        assertEquals("未知专辑", groups.single().name)
    }

    // -----------------------------------------------------------------------
    // 专辑改名后的身份重绑
    // -----------------------------------------------------------------------

    @Test
    fun `专辑标识并入同名既有专辑`() {
        // 库内已有同名专辑：改名曲目并入那张专辑，而不是另起一组同名分组
        assertEquals(20L, albumIdentity(tracks, " 合辑 "))
    }

    @Test
    fun `曲库首见的专辑名分配稳定标识`() {
        val minted = albumIdentity(tracks, "New Album")
        // 负值不与 MediaStore 的正 id 相撞；名字大小写与首尾空白不改变身份，
        // 先后改到同一名字的曲目仍聚成一组
        assertTrue(minted < 0)
        assertEquals(minted, albumIdentity(tracks, " new album "))
        assertTrue(albumIdentity(tracks, "另一张") != minted)
    }

    @Test
    fun `空专辑名按未知专辑处理`() {
        assertEquals(0L, albumIdentity(tracks, "   "))
    }

    @Test
    fun `改名的曲目迁往新专辑分组`() {
        val renamed = tracks[0].copy(
            albumName = "新专辑",
            albumId = albumIdentity(tracks, "新专辑"),
        )
        val groups = albumGroups(listOf(renamed) + tracks.drop(1), "未知专辑")
        // 旧专辑仅剩改名曲目时整组消失，曲目改由新专辑收纳
        assertEquals(listOf("合辑", "新专辑"), groups.map { it.name })
        assertEquals(listOf(2L, 3L), groups[0].trackIds)
        assertEquals(listOf(1L), groups[1].trackIds)
    }

    @Test
    fun `多歌手曲目归属到每位歌手分组`() {
        val groups = artistGroups(tracks, "未知艺术家")
        assertEquals(listOf("artist:乙", "artist:甲"), groups.map { it.key })
        // 合作曲同时进入两位歌手名下：乙为 2、3，甲为 1、2
        assertEquals(listOf(2L, 3L), groups[0].trackIds)
        assertEquals(listOf(1L, 2L), groups[1].trackIds)
    }

    @Test
    fun `单个歌手分组无需构建全部分组`() {
        assertEquals(listOf(1L, 2L), artistGroup(tracks, "甲").trackIds)
        assertEquals("artist:甲", artistGroup(tracks, "甲").key)
    }

    @Test
    fun `去重计数拆分多歌手字段`() {
        assertEquals(2, distinctAlbumCount(tracks))
        // 按整串歌手字段计数会把「甲 / 乙」算成第三个歌手
        assertEquals(2, distinctArtistCount(tracks))
    }

    // -----------------------------------------------------------------------
    // 歌手曲序存档
    // -----------------------------------------------------------------------

    @Test
    fun `保存顺序按排位重排列出的曲目`() {
        assertEquals(listOf(3L, 1L, 2L), applySavedTrackOrder(tracks, listOf(3, 1, 2)).map { it.id })
    }

    @Test
    fun `保存顺序把未列出曲目放在其后`() {
        // 新入库、从未参与排序的曲目一律附在存档曲目之后，且彼此保持原相对次序
        assertEquals(listOf(3L, 1L, 2L), applySavedTrackOrder(tracks, listOf(3)).map { it.id })
        // 无存档即为原顺序
        assertEquals(listOf(1L, 2L, 3L), applySavedTrackOrder(tracks, emptyList()).map { it.id })
    }

    @Test
    fun `保存顺序跳过已不存在的 ID`() {
        // 曲目被删除后存档里的死 id 落空，不影响其余曲目的次序
        assertEquals(listOf(2L, 1L, 3L), applySavedTrackOrder(tracks, listOf(99, 2, 1)).map { it.id })
    }

    // -----------------------------------------------------------------------

    private fun source(key: String): PlaylistSource = PlaylistSource(key, key)

    private fun track(
        id: Long,
        title: String,
        artist: String = "歌手$id",
        albumId: Long = id,
        albumName: String = "专辑$id",
    ): MusicTrack = MusicTrack(
        id = id,
        path = "/music/$id.flac",
        audioUri = "file:///music/$id.flac",
        title = title,
        artist = artist,
        duration = 200_000L,
        albumId = albumId,
        albumName = albumName,
    )
}
