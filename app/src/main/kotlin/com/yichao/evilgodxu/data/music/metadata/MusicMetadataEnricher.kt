package com.yichao.evilgodxu.data.music.metadata

import android.content.Context
import android.net.Uri
import com.yichao.evilgodxu.data.music.model.LyricLine
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.MusicScanner
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.data.music.playback.refreshCurrentMediaItem
import com.yichao.evilgodxu.log.CrashLogManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// 本地元数据补全器：只读音频内嵌信息与本地缓存，**不发起任何网络请求**。
// 扫描、面板显示、列表预取、媒体变更等自动路径都经此处，故其耗时与联网行为会直接影响扫描体验。
// 在线补齐一律由用户手动决策：封面刷新、歌词刷新、在线播放（取所选搜索结果的封面与歌词）。
class MetadataEnricher {
    private companion object {
        // 渐进补全的最小落盘间隔：内存态逐曲更新（列表逐条刷新），落盘按时间节流。
        // 逐曲落盘会让每次写入都重写整份播放列表，首次扫描 N 首即形成 O(N²) 写放大；
        // 完全不落盘又会在进程被杀时丢掉整轮进度，故取时间窗口折中
        const val PROGRESSIVE_PERSIST_MIN_INTERVAL_MS = 1500L
    }

    // 封面后台提取的并发上限：限制同时进行的位图解码数量，
    // 避免大歌单首次启动时内存与 CPU 尖峰导致面板卡顿
    private val metadataDispatcher = Dispatchers.IO.limitedParallelism(4)
    // 歌词后台读取专用调度器：限并发调度器按提交顺序排队，与封面共用同一池会让
    // 歌词任务排在全量封面之后才轮到执行，失去「同时加载」的语义，故分池
    private val lyricDispatcher = Dispatchers.IO.limitedParallelism(4)
    // 按需懒加载专用调度器：与全量补全互不排队，UI 可见项优先提取
    private val onDemandDispatcher = Dispatchers.IO.limitedParallelism(4)
    // 全量补全排期中的曲目 ID：按需请求遇到时让路，由全量任务统一回写
    private val bulkInFlight = ConcurrentHashMap.newKeySet<Long>()
    // 按需提取进行中的曲目 ID：列表快速滚动时同一曲目滚入滚出只执行一次
    private val onDemandInFlight = ConcurrentHashMap.newKeySet<Long>()
    // 补全流程的互斥锁：show / 扫描 / 媒体变更 / 授权后扫描会并发触发 enrich，
    // 不加锁会让同一曲目的封面与歌词被两轮补全交叉回写
    private val enrichMutex = Mutex()

    /**
     * 补全封面/歌词，并按需回收孤儿缓存。
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
            // 补全全程置位：曲库分析的自动触发读到该标记即延后，避免频谱解码与封面解码并发争抢资源
            withContext(Dispatchers.Main) { playbackState.isEnrichingMetadata = true }
            try {
                enrichPlaylistMetadata(context, playbackState)
                if (reclaimOrphans) {
                    // 封面/歌词跨歌单共享：引用集 = 全量库 ∪ 当前歌单，其他歌单仍要使用的文件
                    // 因在全量库中存在引用而不会被误删。libraryTracks 是 getter
                    // （默认库备份 ?: 当前歌单），自定义歌单下已包含默认库备份
                    val referenced = withContext(Dispatchers.Main) {
                        (playbackState.libraryTracks + playbackState.playlist)
                            .flatMap { listOf(it.coverCachePath, it.lyricCachePath) }
                            .toSet()
                    }
                    withContext(Dispatchers.IO) {
                        MusicMetadataCache.reclaimStaleOrphans(context, referenced)
                    }
                }
                // 封面补全后刷新系统媒体面板的当前 MediaItem，避免封面就绪后仍显示空封面
                refreshCurrentMediaItem(playbackState)
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
            // 封面提取与歌词读取都是离线操作、互不依赖，各自独立成支路、各自完成即回写：
            // 串成一支（先等封面跑完再取歌词）会让歌词在全量封面之后才可见，故必须并行且互不等待
            coroutineScope {
                launch { enrichLocalCoversProgressively(context, playbackState, tracks) }
                launch { enrichLyricsProgressively(context, playbackState, tracks) }
            }
        } finally {
            bulkInFlight.removeAll(plannedIds)
        }
    }

    /**
     * 按需补全单曲封面/歌词（懒加载）：纯离线，只读内嵌信息与本地缓存，幂等、重复请求自动跳过，
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
            // 缓存有效性判定与提取同放 IO：列表预取会在一次组合中对全表逐首调用本方法，
            // 「已具备缓存」的判定若留在主线程会形成数千次外部存储 stat
            val updated = withContext(onDemandDispatcher) {
                // 已具备完整缓存则无需补全
                if (hasCompleteMetadata(track)) return@withContext track
                // 封面与歌词都没有可做的补全时直接跳过，判定与全量排期同源：
                // 「曾落盘但文件已失效」不算已尝试失败，仍需重建
                if (!needsCover(track) && !needsLyrics(track)) return@withContext track
                enrichTrack(context, track)
            }
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

    // 封面/歌词是否已具备全部缓存
    private fun hasCompleteMetadata(track: MusicTrack): Boolean =
        coverOwned(track) && track.lyricLines.isNotEmpty()

    // 是否需要对曲目做全量补全
    private fun plansMetadataFor(track: MusicTrack): Boolean =
        needsCover(track) || needsLyrics(track)

    // 封面缓存是否有效且按「标题 - 艺术家」索引命名（当前命名规则，无需重新提取）；
    // 旧版按歌曲 id / 内容哈希命名的缓存不匹配，会按新索引重建
    private fun coverOwned(track: MusicTrack): Boolean =
        MusicMetadataCache.isValid(track.coverCachePath) &&
            MusicMetadataCache.isIndexedCoverName(track.coverCachePath, track.title, track.artist)

    // 封面缺失即需补全。缓存未被当前索引持有时仍要重建的两种情形：一是本轮尚未尝试过
    // （coverFailed 为假，含旧版命名缓存待迁移）；二是引用已落盘、文件却已失效（被删 / 被清空）
    // —— 曾成功落盘说明封面本可得，失败标记不该挡住重建。
    // 文件仍有效时不重试：提取失败只说明当前取不到，缓存本身仍可显示，反复重试会随列表滚动
    // 反复读音频；旧版命名缓存迁移不到就保留，不影响显示
    private fun needsCover(track: MusicTrack): Boolean =
        !coverOwned(track) && (
            !track.coverFailed ||
                (track.coverCachePath.isNotBlank() && !MusicMetadataCache.isValid(track.coverCachePath))
            )

    // 歌词未挂载即需处理：有有效缓存路径时必须读回内容；
    // 仅当既无缓存又已标记失败时才跳过，避免 lyricFailed 挡住缓存歌词的恢复
    private fun needsLyrics(track: MusicTrack): Boolean =
        track.lyricLines.isEmpty() &&
            (MusicMetadataCache.isValid(track.lyricCachePath) || !track.lyricFailed)

    /** 单曲补全：本地封面 → 本地歌词，纯离线；联网补齐不在自动路径内 */
    private suspend fun enrichTrack(
        context: Context,
        track: MusicTrack,
    ): MusicTrack {
        var updated = track
        // 仅有可读取本地音频源才提取封面；纯在线流曲目无本地文件，直接留占位符
        if (needsCover(updated) && (updated.path.isNotBlank() || isLocalFileUri(updated.audioUri))) {
            enrichLocalCover(context, updated)?.let { updated = it }
        }
        if (needsLyrics(updated)) {
            enrichLyric(context, updated)?.let { updated = it }
        }
        return updated
    }

    /** 封面懒加载：并发提取内嵌封面，完成即回写内存态，实现逐张渐进显示 */
    private suspend fun enrichLocalCoversProgressively(
        context: Context,
        playbackState: MusicPlaybackState,
        tracks: List<MusicTrack>,
    ): Set<Long> {
        // 当前播放曲目优先提取，保证首屏封面尽快就绪；限并发调度器按提交顺序排队，优先级仍然生效
        val currentId = withContext(Dispatchers.Main) { playbackState.currentTrack?.id }
        // 筛选与排序放 IO：封面缓存有效性判定含文件 stat，主线程逐首判定的代价远高于内存比较
        val ordered = withContext(Dispatchers.IO) {
            tracks
                // 提取条件与 needsCover 同源：封面缓存按「标题 - 艺术家」索引命名且文件有效才算已具备
                // （旧版按歌曲 id 或内容哈希命名的缓存不匹配，重新提取时自动迁移为新索引）；
                // 另需有可读的本地源——纯在线流曲目无本地文件，直接留占位符
                .filter { needsCover(it) && (it.path.isNotBlank() || isLocalFileUri(it.audioUri)) }
                .sortedWith(compareBy { it.id != currentId })
        }
        if (ordered.isEmpty()) return emptySet()
        // 落盘节流时刻：并发提取的各路共用同一时间窗口
        val lastPersistAt = AtomicLong(0L)

        // 并发提取，并发上限由 metadataDispatcher 保证；每首完成即回写内存态让列表逐张刷新，
        // 落盘按时间窗口节流，兼顾「逐张出现」的观感与「不逐曲重写整份播放列表」的写入量
        val resolved = coroutineScope {
            ordered.map { track ->
                async(metadataDispatcher) {
                    val updated = enrichLocalCover(context, track) ?: return@async null
                    if (updated.coverCachePath.isNotBlank()) {
                        withContext(Dispatchers.Main) {
                            val merged = mergeCoverUpdate(playbackState, updated)
                            if (merged != null) {
                                val now = System.currentTimeMillis()
                                val shouldPersist =
                                    now - lastPersistAt.get() >= PROGRESSIVE_PERSIST_MIN_INTERVAL_MS
                                if (shouldPersist) lastPersistAt.set(now)
                                playbackState.batchUpdateTracks(listOf(merged), persist = shouldPersist)
                            }
                        }
                    }
                    updated
                }
            }.awaitAll().filterNotNull()
        }
        val coveredIds = resolved.filter { it.coverCachePath.isNotBlank() }.mapTo(mutableSetOf()) { it.id }
        // 无内嵌封面的曲目收齐后统一回写，避免逐首触发无意义重组；
        // 该次写入顺带收尾本阶段所有未落盘的内存态变更，无失败项时显式落盘一次
        val failed = resolved.filter { it.coverCachePath.isBlank() }
        withContext(Dispatchers.Main) {
            if (failed.isEmpty()) {
                playbackState.persistPlaylist()
            } else {
                val mergedFailed = failed.mapNotNull { mergeCoverUpdate(playbackState, it) }
                if (mergedFailed.isNotEmpty()) {
                    playbackState.batchUpdateTracks(mergedFailed, persist = true)
                } else {
                    playbackState.persistPlaylist()
                }
            }
        }
        return coveredIds
    }

    // 提取单曲本地封面（内嵌原图 → 系统专辑封面 → 系统缩略图）写入本地缓存；
    // 三层均不可得即标记失败转占位显示，不联网补齐
    private suspend fun enrichLocalCover(context: Context, track: MusicTrack): MusicTrack? = try {
        // 先复用既有索引文件：封面名可由「标题 - 艺术家」直接推出，曲目换 id、重新入库或引用丢失时
        // 无需再解码一遍音频；与歌词按同一索引复用共享缓存同源
        MusicMetadataCache.findCover(context, track.title, track.artist)?.let { path ->
            return track.copy(coverCachePath = path, coverFailed = false)
        }
        val result = MusicScanner.loadAlbumArt(
            context, context.contentResolver,
            Uri.parse(track.audioUri), track.albumId, track.path
        ) ?: return track.copy(
            coverFailed = true,
            // 只丢弃已失效的引用；仍可显示的旧版命名缓存保留，避免提取临时失败时抹掉可用封面
            coverCachePath = track.coverCachePath.takeIf { MusicMetadataCache.isValid(it) }.orEmpty(),
        )
        val cover = result.bitmap
        try {
            val coverPath = MusicMetadataCache.saveCover(context, track.title, track.artist, cover).orEmpty()
            // 旧索引下的文件若已无引用，由扫描后的窗口回收统一处理（连续数天无引用才删），避免误删共享的封面
            track.copy(coverCachePath = coverPath, coverFailed = false)
        } finally {
            cover.recycle()
        }
    } catch (e: Exception) {
        CrashLogManager.logException(
            "MetadataEnricher",
            "提取本地封面失败: 歌曲=${track.title} - ${track.artist} 路径=${track.path}",
            e
        )
        null
    }

    // 是否为可读取本地音频文件（本地文件路径或 MediaStore 本地文件），区分纯在线流媒体资源
    private fun isLocalFileUri(uri: String): Boolean = runCatching {
        val scheme = Uri.parse(uri).scheme
        scheme == "content" || scheme == "file"
    }.getOrDefault(false)

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
        // 并发读取，每首完成即回写内存态：歌词结果基于扫描快照，
        // 合并时以当前内存态为基准、只取歌词字段，避免用快照里的旧封面字段覆盖封面阶段刚写入的结果
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
     *  避免用快照里的旧封面字段覆盖封面阶段已逐首写入的结果；无实际变化返回 null */
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

    /** 合并单曲封面结果：封面基于扫描快照，合并时以当前内存态为基准、只取封面字段，
     *  避免快照里的空歌词字段覆盖歌词阶段已逐首写入的结果（封面解码慢于歌词读取，
     *  整体替换会让刚显示的歌词被抹回占位）；无实际变化返回 null */
    private fun mergeCoverUpdate(
        playbackState: MusicPlaybackState,
        cover: MusicTrack
    ): MusicTrack? {
        val base = playbackState.playlist.firstOrNull { it.id == cover.id } ?: return cover
        // 路径与失败标记均取本次提取结果：成功时落盘新路径并清闩锁，
        // 失败时保留仍有效的旧版命名缓存、丢弃已失效引用
        val merged = base.copy(
            coverCachePath = cover.coverCachePath,
            coverFailed = cover.coverFailed,
        )
        return merged.takeIf { it != base }
    }

    /** 按需补全结果的字段级合并：只应用本次相对传入曲目确实被替换过的封面/歌词字段，
     *  其余字段以当前内存态为准。判定用引用比较，故未命中的分支返回的仍是入参本身 */
    private fun mergeOnDemandUpdate(
        base: MusicTrack,
        original: MusicTrack,
        updated: MusicTrack,
    ): MusicTrack = base.copy(
        coverCachePath = updated.coverCachePath
            .takeIf { it !== original.coverCachePath } ?: base.coverCachePath,
        coverFailed = if (updated.coverFailed != original.coverFailed) updated.coverFailed else base.coverFailed,
        lyricCachePath = updated.lyricCachePath
            .takeIf { it !== original.lyricCachePath } ?: base.lyricCachePath,
        lyricLines = updated.lyricLines
            .takeIf { it !== original.lyricLines } ?: base.lyricLines,
        lyricFailed = if (updated.lyricFailed != original.lyricFailed) updated.lyricFailed else base.lyricFailed,
    )
}
