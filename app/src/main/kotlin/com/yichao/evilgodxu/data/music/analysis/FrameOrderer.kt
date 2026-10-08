package com.yichao.evilgodxu.data.music.analysis

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// 按帧序号恢复并行产出的顺序。
//
// 分析帧的合并与列序都依赖时间序，而多核变换的产出顺序不定：
// 提交方给出帧序号后，此处缓存乱序到达的帧，待缺口补齐再按序交出。
// 缓存的规模由调用方的在途帧数上限约束，不随音频时长增长。
internal class FrameOrderer(
    // 按序交付：在持锁期间被同步调用，实现须是不挂起的短操作
    private val onFrame: (FloatArray) -> Unit,
) {

    private val mutex = Mutex()
    private val pending = HashMap<Long, FloatArray>()
    private var next = 0L

    // 提交一帧；若本次提交补齐了缺口，连同其后连续到达的帧一并交出
    suspend fun submit(index: Long, frame: FloatArray) {
        mutex.withLock {
            pending[index] = frame
            while (true) {
                val ready = pending.remove(next) ?: return
                onFrame(ready)
                next++
            }
        }
    }
}
