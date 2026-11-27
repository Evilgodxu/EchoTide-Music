package com.yichao.evilgodxu.data.music.playback

import com.yichao.evilgodxu.data.music.model.MusicSearchSource
import org.junit.Assert.assertEquals
import org.junit.Test

/** 保存值仅在平台仍可选时生效 */
class SearchSourceRestoreTest {

    private val builtInOnly = MusicSearchSource.builtInKeys

    @Test
    fun `从未切换过平台时回到默认网易`() {
        assertEquals(MusicSearchSource.NETEASE, resolveSearchSource(null, builtInOnly))
    }

    @Test
    fun `保存的内置平台原样恢复`() {
        assertEquals(MusicSearchSource.QQ, resolveSearchSource(MusicSearchSource.QQ, builtInOnly))
        assertEquals(MusicSearchSource.KUGOU, resolveSearchSource(MusicSearchSource.KUGOU, builtInOnly))
    }

    @Test
    fun `保存的自定义平台仍可选时原样恢复`() {
        val available = builtInOnly + "custom"
        assertEquals(
            MusicSearchSource("custom"),
            resolveSearchSource(MusicSearchSource("custom"), available),
        )
    }

    @Test
    fun `保存的自定义平台音源已移除时回退默认网易`() {
        assertEquals(MusicSearchSource.NETEASE, resolveSearchSource(MusicSearchSource("custom"), builtInOnly))
    }
}
