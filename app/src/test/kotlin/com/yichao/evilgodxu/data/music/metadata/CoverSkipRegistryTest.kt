package com.yichao.evilgodxu.data.music.metadata

import com.yichao.evilgodxu.data.music.model.MusicTrack
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 封面「跳过」标记复核：标记命中即短路后续取图，标记又要能随曲目内容变化与应用内封面重写自动失效。
 *
 * 标记一旦无法失效，换过封面的曲目会永久停在占位图；标记一旦提前失效，无封面的曲目会在每次滚入
 * 视口时重新查询系统略缩图并解析音频文件，正是本标记要消除的无效开销。
 */
class CoverSkipRegistryTest {

    private companion object {
        const val AUDIO_URI = "content://media/external/audio/media/1"
        const val FIRST_VERSION_MS = 1_000L
        const val REWRITTEN_VERSION_MS = 2_000L
    }

    @After
    fun tearDown() {
        // 单例跨用例共享，逐个清理避免用例间相互影响
        CoverSkipRegistry.clear()
    }

    private fun track(fileModifiedMs: Long = FIRST_VERSION_MS) = MusicTrack(
        id = 1L,
        path = "/music/a.flac",
        audioUri = AUDIO_URI,
        title = "曲目",
        artist = "歌手",
        duration = 180_000L,
        albumId = 1L,
        fileModifiedMs = fileModifiedMs,
    )

    @Test
    fun `未标记的曲目不跳过`() {
        assertFalse(CoverSkipRegistry.isSkipped(track()))
    }

    @Test
    fun `标记后同一曲目跳过`() {
        CoverSkipRegistry.markSkipped(track())
        assertTrue(CoverSkipRegistry.isSkipped(track()))
    }

    @Test
    fun `曲目内容版本变化后重新取图`() {
        CoverSkipRegistry.markSkipped(track(FIRST_VERSION_MS))
        // 文件被重扫或整体替换：内容版本变化即视为新内容，旧结论不再成立
        assertFalse(CoverSkipRegistry.isSkipped(track(REWRITTEN_VERSION_MS)))
    }

    @Test
    fun `不同曲目互不影响`() {
        CoverSkipRegistry.markSkipped(track())
        val other = track().copy(id = 2L, audioUri = "content://media/external/audio/media/2")
        assertFalse(CoverSkipRegistry.isSkipped(other))
    }

    @Test
    fun `封面重写清空标记`() {
        CoverSkipRegistry.markSkipped(track())
        CoverSkipRegistry.clear()
        assertFalse(CoverSkipRegistry.isSkipped(track()))
    }
}
