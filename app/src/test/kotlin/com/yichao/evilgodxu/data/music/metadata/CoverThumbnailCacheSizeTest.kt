package com.yichao.evilgodxu.data.music.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 索引曲目略缩图内存缓存预算复核：可用堆扣除留给解码与播放的固定余量后，余量即为本缓存的额度，
 * 再夹在上下限之间。
 *
 * 这条换算直接决定列表能驻留多少张封面。落到下限以下会让低内存机型比改造前更差，
 * 滚回已看过的行也要重新取图；失去上限则高内存机型会把堆吃光，
 * 挤压 ExoPlayer 的高解析度缓冲与解码峰值。
 */
class CoverThumbnailCacheSizeTest {

    private companion object {
        const val ONE_MB = 1024 * 1024

        /** 改造前的固定驻留上限：低内存机型不得比这个更小 */
        const val LEGACY_FIXED_BYTES = 32 * ONE_MB

        /** 上限：再高的堆也只用到这里 */
        const val EXPECTED_CAP_BYTES = 128 * ONE_MB

        /** 留给解码与播放的固定余量 */
        const val HEAP_RESERVE_BYTES = 300 * ONE_MB

        /** 常见机型的堆上限（MB）：从低内存机型到旗舰机型 */
        val HEAP_MB = listOf(64L, 128L, 192L, 256L, 384L, 512L, 1024L, 4096L)

        val HEAPS = HEAP_MB.map { it * ONE_MB }
    }

    @Test
    fun `低内存机型不低于改造前的固定值`() {
        HEAPS.forEach { heap ->
            val bytes = coverThumbnailCacheBytes(heap)
            assertTrue(
                "堆 $heap 的计算结果 $bytes 低于改造前的 $LEGACY_FIXED_BYTES，低内存机型反而变差",
                bytes >= LEGACY_FIXED_BYTES,
            )
        }
    }

    @Test
    fun `高内存机型被上限截住`() {
        HEAPS.forEach { heap ->
            assertTrue(
                "堆 $heap 的计算结果超出上限 $EXPECTED_CAP_BYTES",
                coverThumbnailCacheBytes(heap) <= EXPECTED_CAP_BYTES,
            )
        }
        assertEquals(
            EXPECTED_CAP_BYTES.toLong(),
            coverThumbnailCacheBytes(4096L * ONE_MB).toLong(),
        )
    }

    @Test
    fun `随可用堆单调不减`() {
        val bytes = HEAPS.map { coverThumbnailCacheBytes(it) }
        assertEquals("可用堆更大的机型不该得到更小的缓存", bytes.sorted(), bytes)
    }

    @Test
    fun `余量给满后多出的堆才进缓存`() {
        // 堆恰好等于「余量 + 下限」时仍按下限给，再多出的部分才计入缓存
        val justAtFloor = HEAP_RESERVE_BYTES.toLong() + LEGACY_FIXED_BYTES
        assertEquals(
            LEGACY_FIXED_BYTES.toLong(),
            coverThumbnailCacheBytes(justAtFloor).toLong(),
        )
        assertEquals(
            (LEGACY_FIXED_BYTES + ONE_MB).toLong(),
            coverThumbnailCacheBytes(justAtFloor + ONE_MB).toLong(),
        )
    }

    @Test
    fun `大内存机型确实拿到比固定值更大的缓存`() {
        // 384MB 堆落在两端之间：扣除余量后应明显大于改造前的 32MB，又还没够到上限
        val bytes = coverThumbnailCacheBytes(384L * ONE_MB)
        assertTrue("384MB 堆应拿到大于 $LEGACY_FIXED_BYTES 的缓存，实际 $bytes", bytes > LEGACY_FIXED_BYTES)
        assertTrue("384MB 堆不该够到上限 $EXPECTED_CAP_BYTES，实际 $bytes", bytes < EXPECTED_CAP_BYTES)
    }
}
