package com.yichao.evilgodxu.data.music.analysis

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 并行帧序重排复核。
 *
 * 频谱行的合并与列序都依赖时间序，而多核变换的产出顺序不定。
 * 重排器一旦漏放或错序一帧，频谱图会整段错位，且不会有任何显式报错，
 * 因此这里针对「乱序到达」「缺口未补齐」「并发提交」三种情形分别设防。
 */
class FrameOrdererTest {

    @Test
    fun emitsInIndexOrderWhenSubmittedOutOfOrder() = runTest {
        val emitted = mutableListOf<Float>()
        val orderer = FrameOrderer { emitted += it[0] }

        orderer.submit(2L, floatArrayOf(2f))
        orderer.submit(0L, floatArrayOf(0f))
        orderer.submit(1L, floatArrayOf(1f))

        assertEquals(listOf(0f, 1f, 2f), emitted)
    }

    @Test
    fun withholdsFramesUntilTheGapIsFilled() = runTest {
        val emitted = mutableListOf<Float>()
        val orderer = FrameOrderer { emitted += it[0] }

        orderer.submit(1L, floatArrayOf(1f))
        orderer.submit(2L, floatArrayOf(2f))
        // 缺口未补齐时必须压住：一旦提前放出，合并就会按错误的时间序发生
        assertEquals(emptyList<Float>(), emitted)

        orderer.submit(0L, floatArrayOf(0f))
        // 补齐后不仅放出该帧，还应连锁放出此前积压的连续帧
        assertEquals(listOf(0f, 1f, 2f), emitted)
    }

    @Test
    fun keepsOrderWhenSubmittedConcurrently() = runTest {
        val count = 512
        val emitted = mutableListOf<Float>()
        val orderer = FrameOrderer { emitted += it[0] }

        // 打乱提交次序并在多线程上并发提交，模拟多核变换的产出顺序不定
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
