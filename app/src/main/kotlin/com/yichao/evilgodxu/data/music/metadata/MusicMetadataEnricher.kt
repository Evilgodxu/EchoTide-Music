package com.yichao.evilgodxu.data.music.metadata

import android.content.Context
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.data.music.model.LyricLine
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.log.CrashLogManager
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// 本地补全器：只读内嵌与缓存，不联网
// 扫描、预取等都经此，耗时影响扫描体验
// 封面由显示端取，缩略图另落盘
// 在线补齐均由用户手动触发
class MetadataEnricher {
    // 歌词读取限并发，避免大歌单内存/CPU 尖峰
    private val lyricDispatcher = Dispatchers.IO.limitedParallelism(4)
    // 内嵌标签补读限并发，与歌词互不排队
    private val tagDispatcher = Dispatchers.IO.limitedParallelism(4)
    // 按需懒加载调度器，与全量互不排队
    private val onDemandDispatcher = Dispatchers.IO.limitedParallelism(4)
    // 全量补全排期中的曲目 ID，按需请求让路
    private val bulkInFlight = ConcurrentHashMap.newKeySet<Long>()
    // 按需读取进行中的曲目 ID，滚动去重
    private val onDemandInFlight = ConcurrentHashMap.newKeySet<Long>()
    // 补全互斥，避免同一曲目被两轮交叉回写
    private val enrichMutex = Mutex()

    /** 补全歌词；不回收缓存，目录里可能有用户自放的 .lrc */
    suspend fun enrichAndCleanup(
        context: Context,
        playbackState: MusicPlaybackState,
    ) {
        enrichMutex.withLock {
            // 置位后曲库分析延后，避免与频谱争抢资源
            withContext(Dispatchers.Main) { playbackState.isEnrichingMetadata = true }
            try {
                enrichPlaylistMetadata(context, playbackState)
            } finally {
                // 取消路径也要复位，否则自动分析被永久挡住
                withContext(Dispatchers.Main + NonCancellable) {
                    playbackState.isEnrichingMetadata = false
                }
            }
        }
    }

    private suspend fun enrichPlaylistMetadata(context: Context, playbackState: MusicPlaybackState) {
        val tracks = withContext(Dispatchers.Main) { playbackState.playlist.toList() }
        // 判定含文件 stat，放 IO 执行避免主线程掉帧
        val plannedIds = withContext(Dispatchers.IO) {
            tracks.filter { plansMetadataFor(context, it) }.map { it.id }
        }
        bulkInFlight.addAll(plannedIds)
        try {
            // 歌词索引依赖标签：需补标签的一组等标签落定再补歌词
            val unknownArtist = context.getString(R.string.music_scanner_unknown_artist)
            val (needsTags, readyForLyrics) = withContext(Dispatchers.IO) {
                tracks.partition { EmbeddedTagBackfill.isPending(it, unknownArtist) }
            }
            coroutineScope {
                val tagJob = launch { embedTagsProgressively(context, playbackState, needsTags) }
                launch {
                    enrichLyricsProgressively(context, playbackState, readyForLyrics, persist = false)
                }
                tagJob.join()
                enrichLyricsProgressively(context, playbackState, needsTags, persist = true)
            }
        } finally {
            bulkInFlight.removeAll(plannedIds)
        }
    }

    /** 内嵌标签后台补读，读完即回写内存态 */
    private suspend fun embedTagsProgressively(
        context: Context,
        playbackState: MusicPlaybackState,
        targets: List<MusicTrack>,
    ) {
        if (targets.isEmpty()) return
        val changed = coroutineScope {
            targets.map { track ->
                async(tagDispatcher) {
                    val updated = EmbeddedTagBackfill.resolve(context, track) ?: return@async false
                    withContext(Dispatchers.Main) {
                        val merged = mergeTagUpdate(playbackState, track, updated)
                        if (merged != null) playbackState.batchUpdateTracks(listOf(merged), persist = false)
                        merged != null
                    }
                }
            }.awaitAll().any { it }
        }
        if (changed) {
            // 本阶段收尾统一落盘一次
            withContext(Dispatchers.Main) { playbackState.persistPlaylist() }
        }
    }

    /** 只合并本次被替换的字段，无变化返回 null */
    private fun mergeTagUpdate(
        playbackState: MusicPlaybackState,
        original: MusicTrack,
        updated: MusicTrack,
    ): MusicTrack? {
        val base = playbackState.playlist.firstOrNull { it.id == updated.id } ?: return updated
        val merged = base.copy(
            title = updated.title.takeIf { it != original.title } ?: base.title,
            artist = updated.artist.takeIf { it != original.artist } ?: base.artist,
            albumName = updated.albumName.takeIf { it != original.albumName } ?: base.albumName,
        )
        return merged.takeIf { it != base }
    }

    /** 按需补全单曲歌词，纯离线、幂等 */
    internal suspend fun ensureMetadata(
        context: Context,
        playbackState: MusicPlaybackState,
        track: MusicTrack?,
    ) {
        if (track == null) return
        // 全量已排期，交给全量任务回写
        if (track.id in bulkInFlight) return
        // 同一曲目并发去重
        if (!onDemandInFlight.add(track.id)) return
        try {
            // 缓存判定与读取放 IO，避免主线程逐首 stat
            val updated = withContext(onDemandDispatcher) {
                // 已具备完整缓存则无需补全
                if (hasCompleteMetadata(track)) return@withContext track
                // 无可补全时跳过；“曾落盘但失效”仍需重建
                if (!needsLyrics(context, track)) return@withContext track
                enrichLyric(context, track)
            } ?: return
            if (updated != track) {
                withContext(Dispatchers.Main) {
                    // 只应用本次替换的字段，避免抹掉并发写入
                    val base = playbackState.playlist.firstOrNull { it.id == updated.id }
                    val merged = if (base == null) updated else mergeOnDemandUpdate(base, track, updated)
                    if (merged != base) playbackState.updateTrack(merged)
                }
            }
        } finally {
            onDemandInFlight.remove(track.id)
        }
    }

    private fun hasCompleteMetadata(track: MusicTrack): Boolean = track.lyricLines.isNotEmpty()

    private fun plansMetadataFor(context: Context, track: MusicTrack): Boolean = needsLyrics(context, track)

    // 有缓存路径就需读回；仅无缓存且已失败才跳过
    private fun needsLyrics(context: Context, track: MusicTrack): Boolean =
        track.lyricLines.isEmpty() &&
            (MusicMetadataCache.isValid(context, track.lyricCachePath) || !track.lyricFailed)

    /** 并发读本地歌词，每首完成即回写；不联网 */
    private suspend fun enrichLyricsProgressively(
        context: Context,
        playbackState: MusicPlaybackState,
        tracks: List<MusicTrack>,
        // 只在最后一批落盘，避免重复重写歌单
        persist: Boolean = true,
    ) {
        // 当前播放曲目优先读取
        val currentId = withContext(Dispatchers.Main) { playbackState.currentTrack?.id }
        // 筛选与排序放 IO，判定含 stat
        val ordered = withContext(Dispatchers.IO) {
            tracks
                .filter { needsLyrics(context, it) }
                .sortedWith(compareBy { it.id != currentId })
        }
        if (ordered.isEmpty()) return
        // 并发读取，每首完成即回写歌词字段
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
        if (changed && persist) {
            // 本阶段收尾统一落盘一次
            withContext(Dispatchers.Main) { playbackState.persistPlaylist() }
        }
    }

    // 曲目缓存 → .lrc → 内嵌，未命中即失败
    private suspend fun enrichLyric(context: Context, track: MusicTrack): MusicTrack? = try {
        // 复用曲目已关联的缓存，应用手动偏移
        track.lyricCachePath.takeIf { MusicMetadataCache.isValid(context, it) }?.let { path ->
            MusicMetadataCache.loadLyrics(context, path).takeIf { it.isNotEmpty() }?.let { lines ->
                return track.copy(
                    lyricCachePath = path,
                    lyricLines = applyLyricOffset(lines, track.lyricOffsetMs),
                    lyricFailed = false,
                )
            }
        }
        // 其次复用按标题/艺术家落盘的通用缓存
        val existingPath = MusicMetadataCache.findLyrics(context, track.title, track.artist)
        val existingLines = existingPath?.let { MusicMetadataCache.loadLyrics(context, it) }
        if (!existingLines.isNullOrEmpty()) {
            return track.copy(
                lyricCachePath = existingPath,
                lyricLines = applyLyricOffset(existingLines, track.lyricOffsetMs),
                lyricFailed = false,
            )
        }
        // 读内嵌歌词并落盘，避免冷启动重读
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
        // 三处均无则标记失败；联网补齐由用户手动触发
        track.copy(lyricFailed = true)
    } catch (e: Exception) {
        CrashLogManager.logException(
            "MetadataEnricher",
            "读取本地歌词失败: 歌曲=${track.title} - ${track.artist} 路径=${track.path}",
            e
        )
        null
    }

    // 缓存存原始时间戳，读取后按需平移
    private fun applyLyricOffset(lines: List<LyricLine>, offsetMs: Long): List<LyricLine> =
        if (offsetMs != 0L) MusicMetadataCache.shiftLyrics(lines, offsetMs) else lines

    /** 只取歌词字段合并，无变化返回 null */
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

    /** 只合并本次替换的歌词字段 */
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
