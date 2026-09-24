package com.yichao.evilgodxu.data.music.analysis

import android.app.ActivityManager
import android.content.Context
import com.yichao.evilgodxu.data.music.model.MusicTrack
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

// 合并批量分析结果：音质异常与 AI 各识别的命中数
internal data class LibraryAnalysisResult(
    val fakeLosslessCount: Int,
    val aiMusicCount: Int,
)

// 单首曲目的分析排期：null 表示无需解码（非候选 / 大小不可读 / 两路缓存均命中）
private class PendingAnalysis(
    val track: MusicTrack,
    val sizeBytes: Long,
    val fakeWanted: Boolean,
    val aiWanted: Boolean,
)

// 合并单次遍历：音质异常与 AI 识别共享一次整库扫描，同一待分析文件仅解码一次，
// 同时产出两类判定并分别写入各自持久化缓存，消除原先两分析器各自全库遍历导致的
// FLAC 重复解码。进度以「本批需解码文件总数」为基数连续递增，不再出现两段式重跑。
// 预扫（逐首 stat + 读 FLAC 容器头）与频谱解码都在同一限并发调度器上推进：
// 解码独占 MediaCodec 实例，串行执行会让整库耗时随文件数线性增长。
// 已由全曲分析锁定的曲目在排期阶段即排除：其结论是完整分析的产物，分段采样不得改写（见 FullAnalysisLock）。
// forceRecompute 用于「刷新」：忽略既有判定重新分析未锁定曲目，锁定曲目照旧跳过。
internal suspend fun analyzeLibraryCombined(
    context: Context,
    tracks: List<MusicTrack>,
    forceRecompute: Boolean = false,
    onProgress: suspend (checked: Int, total: Int) -> Unit,
): LibraryAnalysisResult {
    val fakeCache = FakeLosslessAnalyzer.cache
    val aiCache = AiMusicAnalyzer.cache
    val lockCache = FullAnalysisLock.cache
    fakeCache.awaitLoaded(context)
    aiCache.awaitLoaded(context)
    FullAnalysisLock.awaitLoaded(context)
    return withContext(Dispatchers.IO) io@{
        // 预扫与解码共用一个限并发调度器：分池会让预扫的全部任务排在解码任务之前，
        // 在「预扫空等解码池」与「解码空等预扫池」之间来回切换，重新退化为两段串行
        val dispatcher = Dispatchers.IO.limitedParallelism(decodeParallelism(context))
        val keepFake = ConcurrentHashMap.newKeySet<String>()
        val keepAi = ConcurrentHashMap.newKeySet<String>()
        val keepLock = ConcurrentHashMap.newKeySet<String>()
        // 缓存命中数：并发排期下逐首累加，须用原子计数
        val cachedFakeCount = AtomicInteger()
        val cachedAiCount = AtomicInteger()

        // 排期单首：至少一个分析器缓存未命中才需解码；缓存命中就地记账
        suspend fun plan(track: MusicTrack): PendingAnalysis? {
            val isFlac = FakeLosslessAnalyzer.isFlacCandidate(track)
            val isAiCandidate = AiMusicAnalyzer.isDecodableCandidate(track)
            if (!isFlac && !isAiCandidate) return null
            val sizeBytes = TrackAudioInfoReader.readFileSize(context, track) ?: return null
            // 全曲分析锁定：跳过解码，只并入保留集合并按既有结论计数——
            // 既不覆盖权威结论，也不让剪枝误删它
            if (FullAnalysisLock.isLocked(track, sizeBytes)) {
                keepLock.add(FullAnalysisLock.cacheKey(track, sizeBytes))
                if (isFlac) {
                    val key = FakeLosslessAnalyzer.cacheKey(track, sizeBytes)
                    keepFake.add(key)
                    if (fakeCache.get(key) == true) cachedFakeCount.incrementAndGet()
                }
                if (isAiCandidate) {
                    val key = AiMusicAnalyzer.cacheKey(track, sizeBytes)
                    keepAi.add(key)
                    if (aiCache.get(key) == true) cachedAiCount.incrementAndGet()
                }
                return null
            }
            var fakeWanted = false
            if (isFlac) {
                val key = FakeLosslessAnalyzer.cacheKey(track, sizeBytes)
                keepFake.add(key)
                val cached = fakeCache.get(key)
                // 强制重算（刷新）时既有判定不可复用：识别策略升级后旧结论须重新校验
                if (cached != null && !forceRecompute) {
                    if (cached) cachedFakeCount.incrementAndGet()
                } else if (!FakeLosslessAnalyzer.isLowSpecFakeLossless(context, track)) {
                    fakeWanted = true
                } else if (forceRecompute) {
                    // 低规格豁免不产出新结论，强制重算时一并丢弃旧结论，避免策略升级后残留
                    fakeCache.map.remove(key)
                }
            }
            var aiWanted = false
            if (isAiCandidate) {
                val key = AiMusicAnalyzer.cacheKey(track, sizeBytes)
                keepAi.add(key)
                val cached = aiCache.get(key)
                if (cached != null && !forceRecompute) {
                    if (cached) cachedAiCount.incrementAndGet()
                } else {
                    aiWanted = true
                }
            }
            return if (fakeWanted || aiWanted) {
                PendingAnalysis(track, sizeBytes, fakeWanted, aiWanted)
            } else {
                null
            }
        }

        // 并发排期但按提交顺序归位：结果与曲库顺序一致，进度数字与曲库顺序可对照
        val pending = coroutineScope {
            tracks.map { track -> async(dispatcher) { plan(track) } }.awaitAll().filterNotNull()
        }
        if (pending.isEmpty()) {
            val staleFake = fakeCache.map.size > keepFake.size
            val staleAi = aiCache.map.size > keepAi.size
            val staleLock = lockCache.map.size > keepLock.size
            // 无待解码文件：仅当存在已删除文件的残留条目时清理，避免无谓写盘
            if (staleFake || staleAi || staleLock) {
                withContext(NonCancellable) {
                    if (staleFake) {
                        fakeCache.map.keys.removeAll { key -> key !in keepFake }
                    }
                    if (staleAi) {
                        aiCache.map.keys.removeAll { key -> key !in keepAi }
                    }
                    if (staleLock) {
                        lockCache.map.keys.removeAll { key -> key !in keepLock }
                    }
                    fakeCache.flush(context)
                    aiCache.flush(context)
                    lockCache.flush(context)
                }
            }
            return@io LibraryAnalysisResult(cachedFakeCount.get(), cachedAiCount.get())
        }
        onProgress(0, pending.size)
        // 进度与命中数：并发完成顺序不固定，统一用原子计数，避免丢失更新
        val checked = AtomicInteger()
        val freshFakeCount = AtomicInteger()
        val freshAiCount = AtomicInteger()
        try {
            coroutineScope {
                pending.map { p ->
                    async(dispatcher) {
                        // 单次解码：一律取首个音频轨（FLAC 文件的唯一音频流即 FLAC 轨，等效于
                        // 单曲入口的指定 mime 解码；采样率/声道由提取器提供，无需容器头兜底）
                        val summary = SpectralDecoder.decodeTrack(p.track, expectedMime = null)
                        if (p.fakeWanted) {
                            val key = FakeLosslessAnalyzer.cacheKey(p.track, p.sizeBytes)
                            // 无法判定的结果也缓存为 false：避免同批文件每次重开对话框都重新分析；
                            // 识别策略升级后由「刷新」清空缓存强制全量重新校验
                            val verdict = summary?.let { FakeLosslessAnalyzer.verdictFromSummary(it) } ?: false
                            fakeCache.map[key] = verdict
                            if (verdict) freshFakeCount.incrementAndGet()
                        }
                        if (p.aiWanted) {
                            val key = AiMusicAnalyzer.cacheKey(p.track, p.sizeBytes)
                            val verdict = summary?.let { AiMusicAnalyzer.verdictFromSummary(it) } ?: false
                            aiCache.map[key] = verdict
                            if (verdict) freshAiCount.incrementAndGet()
                        }
                        val done = checked.incrementAndGet()
                        onProgress(done, pending.size)
                        // 每分析 20 首落盘一次，收窄中断导致的缓存丢失窗口；
                        // 原子自增保证该分支任一时刻只被一路命中，落盘仍由缓存自身互斥串行
                        if (done % CACHE_PERSIST_INTERVAL == 0) {
                            fakeCache.flush(context)
                            aiCache.flush(context)
                        }
                    }
                }.awaitAll()
            }
        } finally {
            // 对话框中途关闭取消协程时也落盘已完成结果，避免已分析结果随进程退出丢失
            withContext(NonCancellable) {
                if (fakeCache.map.size > keepFake.size) {
                    fakeCache.map.keys.removeAll { key -> key !in keepFake }
                }
                if (aiCache.map.size > keepAi.size) {
                    aiCache.map.keys.removeAll { key -> key !in keepAi }
                }
                if (lockCache.map.size > keepLock.size) {
                    lockCache.map.keys.removeAll { key -> key !in keepLock }
                }
                fakeCache.flush(context)
                aiCache.flush(context)
                lockCache.flush(context)
            }
        }
        LibraryAnalysisResult(
            fakeLosslessCount = cachedFakeCount.get() + freshFakeCount.get(),
            aiMusicCount = cachedAiCount.get() + freshAiCount.get(),
        )
    }
}

// 批量分析期间每分析多少首新增文件落盘一次
private const val CACHE_PERSIST_INTERVAL = 20

// 全库分析的并发上限（2~6）：单首待解码任务的 Java 堆开销很小
// （平均功率谱 8KB + FFT 暂存 32KB + 单缓冲 PCM 单声道数组，峰值约 0.2~1MB），
// 真正的约束是同时存活的 MediaCodec 实例（原生内存与解码器实例配额），
// 故上限与文件数无关，按 CPU 核心数定性给出；低内存设备保守取 2，
// 避免与每轮 N 张封面位图解码叠加造成原生内存与 CPU 尖峰。
private fun decodeParallelism(context: Context): Int {
    val lowRam = runCatching {
        (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice == true
    }.getOrDefault(false)
    if (lowRam) return 2
    val cores = Runtime.getRuntime().availableProcessors()
    return when {
        cores >= 8 -> 6
        cores >= 6 -> 5
        cores >= 4 -> 4
        else -> 2
    }
}
