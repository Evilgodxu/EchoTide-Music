package com.yichao.evilgodxu.data.music.playback

import com.yichao.evilgodxu.data.music.model.MusicSearchSource
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 搜索平台恢复复核：保存值只在仍可选时生效。
 *
 * 平台菜单由内置平台与已启用音源声明的自定义平台组成，音源被移除后保存的自定义平台不再有
 * 对应实现，恢复时须回退默认平台而不是把它挂回搜索条。
 */
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
