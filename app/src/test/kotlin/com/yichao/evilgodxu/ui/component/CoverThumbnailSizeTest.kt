package com.yichao.evilgodxu.ui.component

import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 请求档位覆盖显示尺寸并按固定步长分档 */
class CoverThumbnailSizeTest {

    private companion object {
        // 与实现一致的分档步长
        const val STEP_PX = 32

        // 列表行封面显示边长上限（dp）
        const val LIST_COVER_MAX_DP = 28f

        // 覆盖 mdpi 到 xxxhdpi 常见密度
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
