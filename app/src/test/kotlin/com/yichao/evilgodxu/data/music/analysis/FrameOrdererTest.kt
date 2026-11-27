package com.yichao.evilgodxu.data.music.analysis

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test

/** 并行帧重排：乱序、缺口、并发三种情形 */
class FrameOrdererTest {

    @Test
    fun `乱序提交时按序号顺序产出`() = runTest {
        val emitted = mutableListOf<Float>()
        val orderer = FrameOrderer { emitted += it[0] }

        orderer.submit(2L, floatArrayOf(2f))
        orderer.submit(0L, floatArrayOf(0f))
        orderer.submit(1L, floatArrayOf(1f))

        assertEquals(listOf(0f, 1f, 2f), emitted)
    }

    @Test
    fun `缺口补齐前暂不下发`() = runTest {
        val emitted = mutableListOf<Float>()
        val orderer = FrameOrderer { emitted += it[0] }

        orderer.submit(1L, floatArrayOf(1f))
        orderer.submit(2L, floatArrayOf(2f))
        // 缺口未补齐须压住，否则错序合并
        assertEquals(emptyList<Float>(), emitted)

        orderer.submit(0L, floatArrayOf(0f))
        // 补齐后连锁放出积压帧
        assertEquals(listOf(0f, 1f, 2f), emitted)
    }

    @Test
    fun `并发提交仍保持顺序`() = runTest {
        val count = 512
        val emitted = mutableListOf<Float>()
        val orderer = FrameOrderer { emitted += it[0] }

        // 乱序并发提交，模拟多核产出
        val indices = (0 until count).shuffled()
        withContext(Dispatchers.Default) {
            indices.map { index ->
                launch { orderer.submit(index.toLong(), floatArrayOf(index.toFloat())) }
            }.joinAll()
        }

        assertEquals(count, emitted.size)
        assertEquals((0 until count).map { it.toFloat() }, emitted)
    }
}
