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
    fun resolveKeepsCollectionOrderAndSkipsUnknownIds() {
        // 集合顺序即播放顺序，不能按曲库顺序重排；已删除曲目的 id 静默跳过
        assertEquals(listOf(3L, 1L), resolveTracks(tracks, listOf(3, 99, 1)).map { it.id })
    }

    @Test
    fun resolveIsEmptyWhenEitherSideIsEmpty() {
        assertTrue(resolveTracks(tracks, emptyList()).isEmpty())
        assertTrue(resolveTracks(emptyList(), listOf(1L)).isEmpty())
    }

    @Test
    fun recentTracksFollowRecentIds() {
        assertEquals(listOf(2L, 1L), recentTracks(tracks, listOf(2, 1)).map { it.id })
    }

    @Test
    fun smartTrackCountIgnoresMissingTracks() {
        assertEquals(2, smartTrackCount(tracks, listOf(1, 2, 99)))
        assertEquals(0, smartTrackCount(tracks, emptyList()))
    }

    // -----------------------------------------------------------------------
    // 来源解析
    // -----------------------------------------------------------------------

    @Test
    fun smartSourcesResolveRecentAndFavorite() {
        assertEquals(listOf(3L, 1L), resolveSourceTracks(tracks, emptyList(), emptySet(), listOf(3, 1), source("smart:RECENT")).map { it.id })
        assertEquals(listOf(2L), resolveSourceTracks(tracks, emptyList(), setOf(2), emptyList(), source("smart:FAVORITE")).map { it.id })
    }

    @Test
    fun customSourceResolvesPlaylistTrackIds() {
        val playlists = listOf(Playlist(7, "我的", listOf(3, 1), 0L))
        assertEquals(
            listOf(3L, 1L),
            resolveSourceTracks(tracks, playlists, emptySet(), emptyList(), source("custom:7")).map { it.id },
        )
    }

    @Test
    fun albumSourceResolvesByAlbumId() {
        assertEquals(listOf(2L, 3L), resolveSourceTracks(tracks, emptyList(), emptySet(), emptyList(), source("album:20")).map { it.id })
    }

    @Test
    fun artistSourceResolvesEveryListedArtist() {
        // 多歌手曲目「甲 / 乙」在两位歌手名下都应出现
        assertEquals(listOf(1L, 2L), resolveSourceTracks(tracks, emptyList(), emptySet(), emptyList(), source("artist:甲")).map { it.id })
        assertEquals(listOf(2L, 3L), resolveSourceTracks(tracks, emptyList(), emptySet(), emptyList(), source("artist:乙")).map { it.id })
    }

    @Test
    fun unknownSourceResolvesNothing() {
        assertTrue(resolveSourceTracks(tracks, emptyList(), emptySet(), emptyList(), source("whatever")).isEmpty())
    }

    // -----------------------------------------------------------------------
    // 来源有效性
    // -----------------------------------------------------------------------

    @Test
    fun smartSourcesAreAlwaysValid() {
        assertTrue(isViewSourceValid(emptyList(), emptyList(), source("smart:RECENT")))
    }

    @Test
    fun customSourceNeedsAnExistingPlaylist() {
        val playlists = listOf(Playlist(7, "我的", emptyList(), 0L))
        // 空歌单仍是有效状态，判据是歌单是否存在而非是否为空
        assertTrue(isViewSourceValid(playlists, emptyList(), source("custom:7")))
        assertFalse(isViewSourceValid(playlists, emptyList(), source("custom:8")))
    }

    @Test
    fun albumAndArtistSourcesNeedResolvableTracks() {
        assertTrue(isViewSourceValid(emptyList(), tracks, source("album:20")))
        assertFalse(isViewSourceValid(emptyList(), emptyList(), source("album:20")))
    }

    // -----------------------------------------------------------------------
    // 分组
    // -----------------------------------------------------------------------

    @Test
    fun albumGroupsAreKeyedByIdAndNamedAfterFirstTrack() {
        val groups = albumGroups(tracks, "未知专辑")
        assertEquals(listOf("合辑", "甲专辑"), groups.map { it.name })
        assertEquals(listOf("album:20", "album:10"), groups.map { it.key })
        // 按 albumId 分组而非按名字：同名不同专辑不会被并入一块
        assertEquals(listOf(2L, 3L), groups[0].trackIds)
        assertEquals(listOf(1L), groups[1].trackIds)
    }

    @Test
    fun albumGroupFallsBackToUnknownWhenNameIsBlank() {
        val groups = albumGroups(listOf(track(1, "一", albumId = 10, albumName = " ")), "未知专辑")
        assertEquals("未知专辑", groups.single().name)
    }

    @Test
    fun artistGroupsAttributeMultiArtistTracksToEveryArtist() {
        val groups = artistGroups(tracks, "未知艺术家")
        assertEquals(listOf("artist:乙", "artist:甲"), groups.map { it.key })
        // 合作曲同时进入两位歌手名下：乙为 2、3，甲为 1、2
        assertEquals(listOf(2L, 3L), groups[0].trackIds)
        assertEquals(listOf(1L, 2L), groups[1].trackIds)
    }

    @Test
    fun artistGroupResolvesASingleArtistWithoutBuildingAllGroups() {
        assertEquals(listOf(1L, 2L), artistGroup(tracks, "甲").trackIds)
        assertEquals("artist:甲", artistGroup(tracks, "甲").key)
    }

    @Test
    fun distinctCountsSplitMultiArtistFields() {
        assertEquals(2, distinctAlbumCount(tracks))
        // 按整串歌手字段计数会把「甲 / 乙」算成第三个歌手
        assertEquals(2, distinctArtistCount(tracks))
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
