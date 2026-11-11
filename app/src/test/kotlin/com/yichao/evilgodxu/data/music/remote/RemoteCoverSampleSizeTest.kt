package com.yichao.evilgodxu.data.music.remote

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 解码采样率的折半规则：只负责把「整幅读进内存」的代价压下去，
 * 精确收口到目标边长由调用方的缩放步骤完成。
 */
class RemoteCoverSampleSizeTest {

    @Test
    fun `源图不超过目标尺寸时不采样`() {
        assertEquals(1, remoteCoverSampleSize(500, 500, 512))
        assertEquals(1, remoteCoverSampleSize(96, 96, 96))
    }

    @Test
    fun `源图恰为目标两倍时折半一次`() {
        assertEquals(2, remoteCoverSampleSize(200, 200, 100))
    }

    @Test
    fun `大图逐级折半到目标附近`() {
        // 1000 → 500 → 250 → 125：再折半是 62，会小于目标的 96
        assertEquals(8, remoteCoverSampleSize(1000, 1000, 96))
    }

    @Test
    fun `非方形图以最长边为准`() {
        // 长边 2000 折到 125 后不再折半，短边随之只剩 31px，由缩放步骤补齐构图
        assertEquals(16, remoteCoverSampleSize(2000, 500, 96))
    }

    @Test
    fun `目标尺寸非正时退回原尺寸解码`() {
        assertEquals(1, remoteCoverSampleSize(1000, 1000, 0))
    }
}
