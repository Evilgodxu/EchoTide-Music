package com.yichao.evilgodxu.ui.component

import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 列表封面请求尺寸复核：请求档位必须覆盖实际显示尺寸，且落在固定步长的档位上。
 *
 * 请求尺寸同时是内存缓存的键。偏小会让封面被放大渲染而发虚；偏大或随密度取任意像素值，
 * 会让同一首歌多占内存、少驻留张数，反过来加重滚动时的重复取图。
 */
class CoverThumbnailSizeTest {

    private companion object {
        // 与实现一致的分档步长
        const val STEP_PX = 32

        // 与实现一致的列表行封面显示边长上限（dp）
        const val LIST_COVER_MAX_DP = 28f

        // 覆盖常见密度：mdpi 到 xxxhdpi，含常见的非整数密度
        val DENSITIES = listOf(1f, 1.5f, 2f, 2.625f, 3f, 3.5f, 4f)
    }

    @Test
    fun `请求档位不低于实际显示尺寸`() {
        DENSITIES.forEach { density ->
            val size = listCoverThumbnailSize(Density(density))
            val displayPx = LIST_COVER_MAX_DP * density
            assertTrue(
                "density=$density 请求 $size 低于显示尺寸 $displayPx，封面会被放大渲染",
                size >= displayPx,
            )
        }
    }

    @Test
    fun `请求档位按固定步长分档`() {
        DENSITIES.forEach { density ->
            val size = listCoverThumbnailSize(Density(density))
            assertEquals("density=$density 的请求档位 $size 未落在步长上", 0, size % STEP_PX)
        }
    }

    @Test
    fun `请求档位随密度单调不减`() {
        val sizes = DENSITIES.map { listCoverThumbnailSize(Density(it)) }
        assertEquals("请求档位应随密度单调不减", sizes.sorted(), sizes)
    }
}
