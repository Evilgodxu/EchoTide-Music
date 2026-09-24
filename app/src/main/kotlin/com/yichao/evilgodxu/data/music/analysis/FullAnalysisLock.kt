package com.yichao.evilgodxu.data.music.analysis

import android.content.Context
import com.yichao.evilgodxu.data.music.model.MusicTrack

// 全曲分析锁定表：记录哪些曲目的音质与 AI 判定已由完整频谱分析给定。
// 锁定是「以完整分析为准」的落点——曲库分析的分段快速采样遇到锁定曲目一律跳过，
// 结论只能来自 FullSpectrumAnalyzer，两者共用同一份判定缓存，故歌单过滤与曲库统计口径一致。
// 锁定以文件为单位（键含路径、大小与时长），换源/音质升级后音频内容已变，须解除锁定重新参与分析。
// 落盘复用 TrackVerdictCache：值为 true 即已锁定，加载、落盘与剪枝策略与两路判定缓存一致。
internal object FullAnalysisLock {

    val cache = TrackVerdictCache(TrackVerdictCache.FILE_NAME_FULL_ANALYSIS)

    suspend fun awaitLoaded(context: Context) = cache.awaitLoaded(context)

    // 锁定键：与两路判定缓存键同源（前缀 + 路径 + 大小 + 时长），文件内容变化即失效
    fun cacheKey(track: MusicTrack, sizeBytes: Long): String =
        "FULL\u0000${track.path}\u0000$sizeBytes\u0000${track.duration}"

    // 是否已锁定：读内存表，调用前须先 awaitLoaded，供批量分析逐曲判定
    fun isLocked(track: MusicTrack, sizeBytes: Long): Boolean =
        cache.get(cacheKey(track, sizeBytes)) == true

    // 锁定全曲分析结论并落盘；无本地路径的曲目无从做完整分析，不登记
    suspend fun lock(context: Context, track: MusicTrack, sizeBytes: Long) {
        if (track.path.isBlank()) return
        awaitLoaded(context)
        cache.map[cacheKey(track, sizeBytes)] = true
        cache.schedulePersist(context)
    }

    // 解除锁定：换源、音质升级等改变音频内容的操作后按旧文件路径移除，
    // 使该曲重新参与曲库分析；旧路径的判定缓存条目已无曲目指向，由曲库分析的剪枝回收
    suspend fun unlock(context: Context, path: String) {
        if (path.isBlank()) return
        awaitLoaded(context)
        val prefix = "FULL\u0000$path\u0000"
        if (cache.map.keys.removeAll { it.startsWith(prefix) }) {
            cache.flush(context)
        }
    }
}
