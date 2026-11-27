package com.yichao.evilgodxu.data.music.metadata

import com.yichao.evilgodxu.data.music.model.MusicSearchSource
import com.yichao.evilgodxu.data.music.model.NeteaseSongSearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 仅补全不碰已有值；内容相同则跳过 */
class OnlineTagFillTest {

    private val candidate = NeteaseSongSearchResult(
        id = 186016L,
        title = "晴天",
        artist = "周杰伦",
        coverUrl = null,
        source = MusicSearchSource.NETEASE,
        album = "叶惠美",
        releaseDate = "2003-07-31",
        trackNumber = 3,
        discNumber = null,
        copyright = "杰威尔",
    )

    private fun plan(current: Map<TagField, String>, overwrite: Boolean) =
        fillPlan(current, candidate, overwrite).toMap()

    @Test
    fun `仅补全只写空字段`() {
        val result = plan(
            mapOf(TagField.TITLE to "晴天 (Live)", TagField.ALBUM to ""),
            overwrite = false,
        )

        assertEquals("叶惠美", result[TagField.ALBUM])
        assertEquals("2003-07-31", result[TagField.DATE])
        assertEquals("3", result[TagField.TRACK_NUMBER])
        assertEquals("杰威尔", result[TagField.COPYRIGHT])
        assertTrue(TagField.TITLE !in result)
        assertEquals("周杰伦", result[TagField.ARTIST])
    }

    @Test
    fun `全覆盖会改写已有值`() {
        val result = plan(mapOf(TagField.TITLE to "晴天 (Live)"), overwrite = true)

        assertEquals("晴天", result[TagField.TITLE])
        assertEquals("叶惠美", result[TagField.ALBUM])
    }

    @Test
    fun `内容相同的字段不重复写入`() {
        val result = plan(
            mapOf(TagField.ALBUM to "叶惠美", TagField.ARTIST to "周杰伦"),
            overwrite = true,
        )

        assertTrue(TagField.ALBUM !in result)
        assertTrue(TagField.ARTIST !in result)
    }

    @Test
    fun `候选缺失的字段不参与补全`() {
        val sparse = candidate.copy(
            album = null,
            releaseDate = null,
            trackNumber = null,
            copyright = null,
        )
        val result = fillPlan(emptyMap(), sparse, overwrite = false).toMap()

        assertTrue(TagField.ALBUM !in result)
        assertTrue(TagField.DATE !in result)
        assertTrue(TagField.COPYRIGHT !in result)
        assertEquals("晴天", result[TagField.TITLE])
    }

    @Test
    fun `空白值视同空字段`() {
        val result = plan(mapOf(TagField.ALBUM to "   "), overwrite = false)

        assertEquals("叶惠美", result[TagField.ALBUM])
    }
}
