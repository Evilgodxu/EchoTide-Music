package com.yichao.evilgodxu.data.music.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 缓存额度：堆扣除固定余量后夹在上下限之间 */
class CoverThumbnailCacheSizeTest {

    private companion object {
        const val ONE_MB = 1024 * 1024

        /** 旧固定上限，低内存机型不得更小 */
        const val LEGACY_FIXED_BYTES = 32 * ONE_MB

        /** 上限：再高的堆也只用到这里 */
        const val EXPECTED_CAP_BYTES = 128 * ONE_MB

        /** 留给解码与播放的固定余量 */
        const val HEAP_RESERVE_BYTES = 300 * ONE_MB

        /** 常见机型的堆上限（MB） */
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
        // 堆为余量+下限时给下限，再多才计入
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
        // 384MB 堆：大于 32MB 未到上限
        val bytes = coverThumbnailCacheBytes(384L * ONE_MB)
        assertTrue("384MB 堆应拿到大于 $LEGACY_FIXED_BYTES 的缓存，实际 $bytes", bytes > LEGACY_FIXED_BYTES)
        assertTrue("384MB 堆不该够到上限 $EXPECTED_CAP_BYTES，实际 $bytes", bytes < EXPECTED_CAP_BYTES)
    }
}
