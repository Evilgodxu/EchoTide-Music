package com.yichao.evilgodxu.data.music.metadata

import android.content.Context
import com.yichao.evilgodxu.data.music.model.LyricLine
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.log.CrashLogManager
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// 本地元数据补全器：只读音频内嵌信息与本地缓存，**不发起任何网络请求**。
// 扫描、面板显示、列表预取、媒体变更等自动路径都经此处，故其耗时与联网行为会直接影响扫描体验。
// 封面不在此补全：封面图由显示端按需取自系统略缩图或音频内嵌封面，应用不落盘封面缓存；
// 在线补齐一律由用户手动决策：封面刷新、歌词刷新、在线播放（取所选搜索结果的歌词）。
class MetadataEnricher {
    // 歌词后台读取专用调度器：限并发，避免大歌单首次启动时内存与 CPU 尖峰导致面板卡顿
    private val lyricDispatcher = Dispatchers.IO.limitedParallelism(4)
    // 按需懒加载专用调度器：与全量补全互不排队，UI 可见项优先读取
    private val onDemandDispatcher = Dispatchers.IO.limitedParallelism(4)
    // 全量补全排期中的曲目 ID：按需请求遇到时让路，由全量任务统一回写
    private val bulkInFlight = ConcurrentHashMap.newKeySet<Long>()
    // 按需读取进行中的曲目 ID：列表快速滚动时同一曲目滚入滚出只执行一次
    private val onDemandInFlight = ConcurrentHashMap.newKeySet<Long>()
    // 补全流程的互斥锁：show / 扫描 / 媒体变更 / 授权后扫描会并发触发 enrich，
    // 不加锁会让同一曲目的歌词被两轮补全交叉回写
    private val enrichMutex = Mutex()

    /**
     * 补全歌词，并按需回收孤儿缓存。
     *
     * [reclaimOrphans] 仅在调用方刚完成一次可信的全量扫描时置 true：孤儿回收依赖完整引用集，
     * 其余入口（切歌单、下载完成、媒体变更、权限回调）的引用集可能缺失，一律不参与回收。
     * 回收本身也是窗口策略（见 [MusicMetadataCache.reclaimStaleOrphans]），不会即时删除。
     */
    suspend fun enrichAndCleanup(
        context: Context,
        playbackState: MusicPlaybackState,
        reclaimOrphans: Boolean = false,
    ) {
        enrichMutex.withLock {
            // 补全全程置位：曲库分析的自动触发读到该标记即延后，避免频谱解码与元数据读取并发争抢资源
            withContext(Dispatchers.Main) { playbackState.isEnrichingMetadata = true }
            try {
                enrichPlaylistMetadata(context, playbackState)
                if (reclaimOrphans) {
                    // 歌词缓存跨歌单共享：引用集 = 全量库 ∪ 当前歌单，其他歌单仍要使用的文件
                    // 因在全量库中存在引用而不会被误删。libraryTracks 是 getter
                    // （默认库备份 ?: 当前歌单），自定义歌单下已包含默认库备份
                    val referenced = withContext(Dispatchers.Main) {
                        (playbackState.libraryTracks + playbackState.playlist)
                            .map { it.lyricCachePath }
                            .toSet()
                    }
                    withContext(Dispatchers.IO) {
                        MusicMetadataCache.reclaimStaleOrphans(context, referenced)
                    }
                }
            } finally {
                // 取消（切歌单 / 退出）路径同样要复位，否则自动分析会被永久挡住
                withContext(Dispatchers.Main + NonCancellable) {
                    playbackState.isEnrichingMetadata = false
                }
            }
        }
    }

    private suspend fun enrichPlaylistMetadata(context: Context, playbackState: MusicPlaybackState) {
        val tracks = withContext(Dispatchers.Main) { playbackState.playlist.toList() }
        // 「是否需要补全」的判定含逐个缓存文件的 stat（isValid → File.isFile/length），
        // 放 IO 执行：外部存储路径经 FUSE 的 stat 开销远高于内存判断，数千首在主线程判定会直接掉帧
        val plannedIds = withContext(Dispatchers.IO) {
            tracks.filter { plansMetadataFor(it) }.map { it.id }
        }
        bulkInFlight.addAll(plannedIds)
        try {
            enrichLyricsProgressively(context, playbackState, tracks)
        } finally {
            bulkInFlight.removeAll(plannedIds)
        }
    }

    /**
     * 按需补全单曲歌词（懒加载）：纯离线，只读内嵌信息与本地缓存，幂等、重复请求自动跳过，
     * 供 UI 可见项（当前播放、列表滚入视口）触发，避免每次启动全量补全拖慢首屏。
     */
    internal suspend fun ensureMetadata(
        context: Context,
        playbackState: MusicPlaybackState,
        track: MusicTrack?,
    ) {
        if (track == null) return
        // 全量补全已排期该曲目，由全量任务统一回写
        if (track.id in bulkInFlight) return
        // 同一曲目并发去重：列表快速滚动时滚入滚出只执行一次
        if (!onDemandInFlight.add(track.id)) return
        try {
            // 缓存有效性判定与读取同放 IO：列表预取会在一次组合中对全表逐首调用本方法，
            // 「已具备缓存」的判定若留在主线程会形成数千次外部存储 stat
            val updated = withContext(onDemandDispatcher) {
                // 已具备完整缓存则无需补全
                if (hasCompleteMetadata(track)) return@withContext track
                // 没有可做的补全时直接跳过，判定与全量排期同源：
                // 「曾落盘但文件已失效」不算已尝试失败，仍需重建
                if (!needsLyrics(track)) return@withContext track
                enrichLyric(context, track)
            } ?: return
            if (updated != track) {
                withContext(Dispatchers.Main) {
                    // 传入曲目可能落后于内存态（UI 组合期间捕获的快照），与全量支路并发时
                    // 整体替换会抹掉对方刚写入的字段，故只应用本次实际替换过的字段
                    val base = playbackState.playlist.firstOrNull { it.id == updated.id }
                    val merged = if (base == null) updated else mergeOnDemandUpdate(base, track, updated)
                    if (merged != base) playbackState.updateTrack(merged)
                }
            }
        } finally {
            onDemandInFlight.remove(track.id)
        }
    }

    // 是否已具备歌词缓存
    private fun hasCompleteMetadata(track: MusicTrack): Boolean = track.lyricLines.isNotEmpty()

    // 是否需要对曲目做全量补全
    private fun plansMetadataFor(track: MusicTrack): Boolean = needsLyrics(track)

    // 歌词未挂载即需处理：有有效缓存路径时必须读回内容；
    // 仅当既无缓存又已标记失败时才跳过，避免 lyricFailed 挡住缓存歌词的恢复
    private fun needsLyrics(track: MusicTrack): Boolean =
        track.lyricLines.isEmpty() &&
            (MusicMetadataCache.isValid(track.lyricCachePath) || !track.lyricFailed)

    /** 歌词懒加载：并发读取本地歌词，每首完成即回写内存态（面板/列表逐首刷新）；不联网 */
    private suspend fun enrichLyricsProgressively(
        context: Context,
        playbackState: MusicPlaybackState,
        tracks: List<MusicTrack>,
    ) {
        // 当前播放曲目优先读取，保证面板歌词先于整库就绪；限并发调度器按提交顺序排队，优先级仍然生效
        val currentId = withContext(Dispatchers.Main) { playbackState.currentTrack?.id }
        // 筛选与排序放 IO：歌词缓存有效性判定含文件 stat，主线程逐首判定代价高
        val ordered = withContext(Dispatchers.IO) {
            tracks
                .filter { needsLyrics(it) }
                .sortedWith(compareBy { it.id != currentId })
        }
        if (ordered.isEmpty()) return
        // 并发读取，每首完成即回写内存态（只取歌词字段，不做整体替换）
        val changed = coroutineScope {
            ordered.map { track ->
                async(lyricDispatcher) {
                    val updated = enrichLyric(context, track) ?: return@async false
                    withContext(Dispatchers.Main) {
                        val merged = mergeLyricUpdate(playbackState, updated)
                        if (merged != null) playbackState.batchUpdateTracks(listOf(merged), persist = false)
                        merged != null
                    }
                }
            }.awaitAll().any { it }
        }
        // 逐首只更新内存态，本阶段收尾统一落盘一次，避免逐曲重写整份播放列表
        if (changed) {
            withContext(Dispatchers.Main) { playbackState.persistPlaylist() }
        }
    }

    // 读取单曲本地歌词：曲目缓存 → 内嵌歌词 → 共享 .lrc 缓存；三处均未命中即标记失败
    private suspend fun enrichLyric(context: Context, track: MusicTrack): MusicTrack? = try {
        // 优先直接复用曲目已关联的歌词缓存（冷启动恢复残留的路径），按需读取并应用手动偏移
        track.lyricCachePath.takeIf { MusicMetadataCache.isValid(it) }?.let { path ->
            MusicMetadataCache.loadLyrics(path).takeIf { it.isNotEmpty() }?.let { lines ->
                return track.copy(
                    lyricCachePath = path,
                    lyricLines = applyLyricOffset(lines, track.lyricOffsetMs),
                    lyricFailed = false,
                )
            }
        }
        // 其次读取本地音频内嵌歌词：文件自带歌词优先于共享缓存；
        // 读回后落盘为歌词缓存，避免冷启动重复读取音频文件头部
        if (track.isLocalAudioSource) {
            MusicEmbeddedLyricReader.read(context, track).takeIf { it.isNotEmpty() }?.let { lines ->
                val lyricPath = MusicMetadataCache.saveLyrics(context, track.title, track.artist, lines).orEmpty()
                return track.copy(
                    lyricCachePath = lyricPath,
                    lyricLines = applyLyricOffset(lines, track.lyricOffsetMs),
                    lyricFailed = false,
                )
            }
        }
        // 再次复用按"标题 - 艺术家"落盘的通用歌词缓存（在线播放/手动刷新保存的 .lrc）
        val existingPath = MusicMetadataCache.findLyrics(context, track.title, track.artist)
        val existingLines = existingPath?.let { MusicMetadataCache.loadLyrics(it) }
        if (!existingLines.isNullOrEmpty()) {
            return track.copy(
                lyricCachePath = existingPath,
                lyricLines = applyLyricOffset(existingLines, track.lyricOffsetMs),
                lyricFailed = false,
            )
        }
        // 本地三处均无歌词：标记失败并转占位显示，同时避免后续补全反复重扫同一曲目。
        // 联网补齐由用户手动触发（歌词刷新），不在自动路径内
        track.copy(lyricFailed = true)
    } catch (e: Exception) {
        CrashLogManager.logException(
            "MetadataEnricher",
            "读取本地歌词失败: 歌曲=${track.title} - ${track.artist} 路径=${track.path}",
            e
        )
        null
    }

    // 应用手动时间偏移：缓存文件保存的始终是原始时间戳，读取后按需平移
    private fun applyLyricOffset(lines: List<LyricLine>, offsetMs: Long): List<LyricLine> =
        if (offsetMs != 0L) MusicMetadataCache.shiftLyrics(lines, offsetMs) else lines

    /** 合并单曲歌词结果：歌词基于扫描快照，合并时以当前内存态为基准、只取歌词字段，
     *  避免用快照里的其它字段覆盖并发支路已写入的结果；无实际变化返回 null */
    private fun mergeLyricUpdate(
        playbackState: MusicPlaybackState,
        lyric: MusicTrack
    ): MusicTrack? {
        val base = playbackState.playlist.firstOrNull { it.id == lyric.id } ?: return lyric
        val merged = base.copy(
            lyricCachePath = lyric.lyricCachePath.ifEmpty { base.lyricCachePath },
            lyricLines = lyric.lyricLines.ifEmpty { base.lyricLines },
            lyricFailed = base.lyricFailed || lyric.lyricFailed,
        )
        return merged.takeIf { it != base }
    }

    /** 按需补全结果的字段级合并：只应用本次相对传入曲目确实被替换过的歌词字段，
     *  其余字段以当前内存态为准。判定用引用比较，故未命中的分支返回的仍是入参本身 */
    private fun mergeOnDemandUpdate(
        base: MusicTrack,
        original: MusicTrack,
        updated: MusicTrack,
    ): MusicTrack = base.copy(
        lyricCachePath = updated.lyricCachePath
            .takeIf { it !== original.lyricCachePath } ?: base.lyricCachePath,
        lyricLines = updated.lyricLines
            .takeIf { it !== original.lyricLines } ?: base.lyricLines,
        lyricFailed = if (updated.lyricFailed != original.lyricFailed) updated.lyricFailed else base.lyricFailed,
    )
}
