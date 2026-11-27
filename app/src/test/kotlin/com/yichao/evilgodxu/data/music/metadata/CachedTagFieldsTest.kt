package com.yichao.evilgodxu.data.music.metadata

import com.yichao.evilgodxu.data.music.model.MusicSearchSource
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.model.NeteaseSongSearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 只断言写入集合：缺字段保留原值，空串清标签 */
class CachedTagFieldsTest {

    private val track = MusicTrack(
        id = 1L,
        path = "",
        audioUri = "https://example.com/a.mp3",
        title = "晴天",
        artist = "周杰伦",
        duration = 269_000L,
        albumId = 0L,
    )

    private fun result(
        album: String? = null,
        releaseDate: String? = null,
        trackNumber: Int? = null,
        discNumber: Int? = null,
        copyright: String? = null,
    ) = NeteaseSongSearchResult(
        id = 186016L,
        title = "晴天",
        artist = "周杰伦",
        coverUrl = null,
        source = MusicSearchSource.NETEASE,
        album = album,
        releaseDate = releaseDate,
        trackNumber = trackNumber,
        discNumber = discNumber,
        copyright = copyright,
    )

    @Test
    fun `平台给出的标签字段全部进入写入集合`() {
        val fields = cachedTagFields(
            track,
            result(
                album = "叶惠美",
                releaseDate = "2003-07-31",
                trackNumber = 3,
                discNumber = 1,
                copyright = "杰威尔",
            ),
            lyrics = "[00:00.00]故事的小黄花",
        )

        assertEquals("晴天", fields[TagField.TITLE])
        assertEquals("周杰伦", fields[TagField.ARTIST])
        assertEquals("叶惠美", fields[TagField.ALBUM])
        assertEquals("2003-07-31", fields[TagField.DATE])
        assertEquals("3", fields[TagField.TRACK_NUMBER])
        assertEquals("1", fields[TagField.DISC_NUMBER])
        assertEquals("杰威尔", fields[TagField.COPYRIGHT])
        assertEquals("[00:00.00]故事的小黄花", fields[TagField.LYRICS])
    }

    @Test
    fun `平台缺失的字段不参与写入`() {
        val fields = cachedTagFields(track, result(), lyrics = null)

        assertNull(fields[TagField.ALBUM])
        assertNull(fields[TagField.DATE])
        assertNull(fields[TagField.TRACK_NUMBER])
        assertNull(fields[TagField.DISC_NUMBER])
        assertNull(fields[TagField.COPYRIGHT])
        assertNull(fields[TagField.LYRICS])
    }

    @Test
    fun `结果无专辑时回落到曲目已有的专辑名`() {
        val fields = cachedTagFields(track.copy(albumName = "范特西"), result(), lyrics = null)

        assertEquals("范特西", fields[TagField.ALBUM])
    }

    @Test
    fun `曲目标题为空时以搜索结果的标题落盘`() {
        val fields = cachedTagFields(track.copy(title = "", artist = ""), result(), lyrics = null)

        assertEquals("晴天", fields[TagField.TITLE])
        assertEquals("周杰伦", fields[TagField.ARTIST])
    }

    @Test
    fun `平台提供响度时一并写入增益与峰值`() {
        val fields = cachedTagFields(
            track,
            result().copy(replayGainTrackGainDb = -7.603, replayGainTrackPeak = 0.946),
            lyrics = null,
        )

        assertEquals("-7.60 dB", fields[TagField.REPLAY_GAIN_TRACK_GAIN])
        assertEquals("0.946000", fields[TagField.REPLAY_GAIN_TRACK_PEAK])
    }

    @Test
    fun `平台未提供响度时不写增益`() {
        val fields = cachedTagFields(track, result(), lyrics = null)

        assertNull(fields[TagField.REPLAY_GAIN_TRACK_GAIN])
        assertNull(fields[TagField.REPLAY_GAIN_TRACK_PEAK])
    }
}
