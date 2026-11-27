package com.yichao.evilgodxu.data.music.analysis

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// 缓存乱序帧，待缺口补齐再按序交出
// 缓存规模受在途帧数上限约束
internal class FrameOrderer(
    // 持锁期间同步调用，须不挂起
    private val onFrame: (FloatArray) -> Unit,
) {

    private val mutex = Mutex()
    private val pending = HashMap<Long, FloatArray>()
    private var next = 0L

    // 补齐缺口后连同后续帧一并交出
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
