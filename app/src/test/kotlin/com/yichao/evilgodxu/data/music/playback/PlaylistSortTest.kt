package com.yichao.evilgodxu.data.music.playback

import com.yichao.evilgodxu.data.music.model.MusicTrack
import org.junit.Assert.assertEquals
import org.junit.Test

/** 默认顺序为锚点+歌手块+专辑块 */
class PlaylistSortTest {

    // 歌手解析

    @Test
    fun `歌手按支持的分隔符切分`() {
        assertEquals(listOf("A", "B"), parseTrackArtists("A、B"))
        assertEquals(listOf("A", "B"), parseTrackArtists("A&B"))
        assertEquals(listOf("A", "B"), parseTrackArtists("A / B"))
        assertEquals(listOf("A", "B"), parseTrackArtists("A;B"))
        assertEquals(listOf("A"), parseTrackArtists(" A "))
    }

    // 自然序

    @Test
    fun `前导数字按数值排序且优先`() {
        // 自然序按数值，数字开头者靠前
        val tracks = listOf(track(1, "明天"), track(2, "10 年"), track(3, "2 年"))
        assertEquals(listOf("2 年", "10 年", "明天"), titles(sortTracks(tracks, PlaylistSortField.TITLE, false)))
    }

    @Test
    fun `中文数字按数值处理`() {
        val tracks = listOf(track(1, "十年"), track(2, "二字"), track(3, "百年"), track(4, "月光"))
        assertEquals(
            listOf("二字", "十年", "百年", "月光"),
            titles(sortTracks(tracks, PlaylistSortField.TITLE, false)),
        )
    }

    // 各字段

    @Test
    fun `收藏排序把喜欢的置前，其余按标题自然序`() {
        val tracks = listOf(
            track(1, "C"),
            track(2, "10 年", isFavorite = true),
            track(3, "B", isFavorite = true),
            track(4, "A"),
        )
        // 收藏在前，同态内按标题自然序
        assertEquals(
            listOf("10 年", "B", "A", "C"),
            titles(sortTracks(tracks, PlaylistSortField.FAVORITE, false)),
        )
    }

    @Test
    fun `修改时间把最新的置前`() {
        val tracks = listOf(
            track(1, "旧", fileModifiedMs = 100),
            track(2, "新", fileModifiedMs = 300),
            track(3, "中", fileModifiedMs = 200),
        )
        assertEquals(
            listOf("新", "中", "旧"),
            titles(sortTracks(tracks, PlaylistSortField.MODIFIED_TIME, false)),
        )
    }

    @Test
    fun `歌手相同时回落到标题`() {
        val tracks = listOf(
            track(1, "B", artist = "同"),
            track(2, "A", artist = "同"),
        )
        assertEquals(listOf("A", "B"), titles(sortTracks(tracks, PlaylistSortField.ARTIST, false)))
    }

    @Test
    fun `专辑相同时回落到专辑 ID 再回落标题`() {
        val tracks = listOf(
            track(1, "B", albumName = "同名", albumId = 2),
            track(2, "A", albumName = "同名", albumId = 1),
            track(3, "C", albumName = "同名", albumId = 1),
        )
        // 同名专辑按 albumId 区分
        assertEquals(listOf("A", "C", "B"), titles(sortTracks(tracks, PlaylistSortField.ALBUM, false)))
    }

    @Test
    fun `时长相同时回落到标题`() {
        val tracks = listOf(
            track(1, "B", duration = 100),
            track(2, "A", duration = 100),
            track(3, "C", duration = 50),
        )
        assertEquals(listOf("C", "A", "B"), titles(sortTracks(tracks, PlaylistSortField.DURATION, false)))
    }

    @Test
    fun `降序反转排序结果`() {
        val tracks = listOf(track(1, "A"), track(2, "B"), track(3, "C"))
        assertEquals(
            listOf("C", "B", "A"),
            titles(sortTracks(tracks, PlaylistSortField.TITLE, descending = true)),
        )
    }

    // 默认顺序

    @Test
    fun `默认顺序以首曲为锚，按歌手再按专辑分组`() {
        val tracks = listOf(
            track(1, "斑马", artist = "周杰伦", albumName = "七里香", albumId = 1),
            track(2, "安静", artist = "周杰伦", albumName = "七里香", albumId = 1),
            track(3, "成都", artist = "赵雷", albumName = "无法长大", albumId = 2),
            track(4, "理想", artist = "赵雷", albumName = "无法长大", albumId = 2),
            track(5, "安和桥", artist = "宋冬野", albumName = "安和桥北", albumId = 3),
            track(6, "董小姐", artist = "宋冬野", albumName = "摩登天空", albumId = 4),
            track(7, "莉莉安", artist = "陈粒", albumName = "摩登天空", albumId = 4),
        )
        // 锚点固定；同歌手≥2 首聚拢
        assertEquals(
            listOf("安和桥", "安静", "斑马", "成都", "理想", "董小姐", "莉莉安"),
            titles(sortTracks(tracks, PlaylistSortField.DEFAULT, false)),
        )
    }

    @Test
    fun `默认顺序无论升降序锚点不变`() {
        val tracks = listOf(
            track(1, "斑马", artist = "周杰伦", albumName = "七里香", albumId = 1),
            track(2, "安静", artist = "周杰伦", albumName = "七里香", albumId = 1),
        )
        val ascending = sortTracks(tracks, PlaylistSortField.DEFAULT, false)
        val descending = sortTracks(tracks, PlaylistSortField.DEFAULT, true)
        assertEquals("安静", ascending.first().title)
        // 逆序只翻转序列，不重挑锚点
        assertEquals("安静", descending.last().title)
    }

    @Test
    fun `默认顺序优先歌手分块而非标题序列`() {
        val tracks = listOf(
            track(1, "A", artist = "乙", albumName = "乙的专辑", albumId = 3),
            track(2, "B", artist = "甲", albumName = "甲的专辑", albumId = 2),
            track(3, "C", artist = "乙", albumName = "乙的专辑", albumId = 3),
            track(4, "D", artist = "甲", albumName = "甲的专辑", albumId = 2),
            track(5, "E", artist = "甲 / 丙", albumName = "合辑", albumId = 1),
        )
        // 甲 3 首成歌手块，整体排在专辑块前
        assertEquals(listOf("A", "B", "D", "E", "C"), titles(sortTracks(tracks, PlaylistSortField.DEFAULT, false)))
    }

    private fun titles(tracks: List<MusicTrack>): List<String> = tracks.map { it.title }

    private fun track(
        id: Long,
        title: String,
        artist: String = "歌手$id",
        duration: Long = 200_000L,
        albumId: Long = id,
        albumName: String = "专辑$id",
        fileModifiedMs: Long = 0L,
        isFavorite: Boolean = false,
    ): MusicTrack = MusicTrack(
        id = id,
        path = "/music/$id.flac",
        audioUri = "file:///music/$id.flac",
        title = title,
        artist = artist,
        duration = duration,
        albumId = albumId,
        albumName = albumName,
        fileModifiedMs = fileModifiedMs,
        isFavorite = isFavorite,
    )
}
