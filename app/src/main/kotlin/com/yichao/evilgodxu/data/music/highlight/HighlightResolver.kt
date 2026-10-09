package com.yichao.evilgodxu.data.music.highlight

import android.content.Context
import com.yichao.evilgodxu.data.music.metadata.MusicMetadataCache
import com.yichao.evilgodxu.data.music.model.LyricLine
import com.yichao.evilgodxu.data.music.model.MusicTrack
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 副歌片段解析与缓存。
 *
 * 定位本身是纯计算，唯一有代价的一步是「歌词从哪来」：曲目歌词由后台补全渐进挂载到内存
 * （见 MusicMetadataEnricher），尚未挂载时须读歌词缓存文件。本类把这一步收敛到一处并缓存结果，
 * 于是队列构建时只需查内存映射，不必逐首读盘。
 *
 * 缓存按「歌词指纹」失效：歌词被补全或手动刷新后指纹改变，旧片段作废重算，
 * 避免歌词更新了而片段仍指向旧位置。
 */
internal object HighlightResolver {

    // 已解析条目：指纹用于判定缓存是否仍然对应当前歌词
    private class Entry(val fingerprint: String, val highlight: Highlight?)

    private val cache = ConcurrentHashMap<Long, Entry>()

    /**
     * 取已缓存的片段，不触发解析、不读盘 —— 供播放回调等不宜阻塞的位置同步判定。
     *
     * 三态区分「歌词换了/还没解析」与「确实没有副歌」：前者不该让调用方跳过该曲，
     * 后者才是「定位不到」。
     */
    fun cachedState(trackId: Long, fingerprint: String): CacheState {
        val entry = cache[trackId] ?: return CacheState.Unknown
        if (entry.fingerprint != fingerprint) return CacheState.Unknown
        return entry.highlight?.let { CacheState.Found(it) } ?: CacheState.Miss
    }

    /** 歌词指纹：行数与首末时间戳足以识别歌词是否被替换 */
    fun fingerprint(track: MusicTrack): String {
        val lines = track.lyricLines
        if (lines.isEmpty()) return "none:${track.lyricCachePath}"
        return "${lines.size}:${lines.first().timeMs}:${lines.last().timeMs}"
    }

    /**
     * 解析曲目片段，命中缓存时直接返回。
     *
     * 歌词优先取内存（后台补全通常已挂载），其次读歌词缓存文件 —— 读盘在 IO 线程进行。
     * 歌词存在但定位不出副歌时返回 null 并记入缓存，避免同一曲目反复读盘；
     * 歌词尚不存在时不写缓存（见下），两种 null 对调用方的含义不同。
     */
    suspend fun resolve(context: Context, track: MusicTrack): Highlight? {
        val fingerprint = fingerprint(track)
        cache[track.id]?.takeIf { it.fingerprint == fingerprint }?.let { return it.highlight }
        val lines = withContext(Dispatchers.IO) { lyricLines(context, track) }
        // 歌词尚未就绪（未挂载、未缓存）不等于「没有副歌」：此时不写缓存，保持未解析态。
        // 若把这种情况一并记成「不可定位」，冷启动时歌词尚未补全的曲库会被整库判为无副歌，
        // 心动模式会一路跳过并触发回绕保护而自行退出 —— 表现得就像模式坏了。
        // 调用方据「未解析」按整曲播放，待歌词补全后再解析出真正的片段
        if (lines.isEmpty()) return null
        val highlight = HighlightLocator.locate(lines, effectiveDurationMs(track, lines))
        cache[track.id] = Entry(fingerprint, highlight)
        return highlight
    }

    private fun lyricLines(context: Context, track: MusicTrack): List<LyricLine> {
        if (track.lyricLines.isNotEmpty()) return track.lyricLines
        val path = track.lyricCachePath
        if (MusicMetadataCache.isValid(path)) return MusicMetadataCache.loadLyrics(path)
        // 曲目的歌词缓存路径不随播放列表落盘，冷启动后要到后台补全跑过才有值。此处按
        // 「歌名 - 艺术家」再找一次 —— 与元数据补全同一套定位规则，在线播放或手动刷新落盘的
        // .lrc 因此在补全完成前也能用上，心动模式不必等一轮补全才生效。
        // 手动微调的偏移量不在此应用：恒定平移不改变哪一段在重复，副歌区间不受它影响
        val byName = MusicMetadataCache.findLyrics(context, track.title, track.artist)
        return byName?.let { MusicMetadataCache.loadLyrics(it) }.orEmpty()
    }

    // 时长缺失时的兜底：以末行歌词为锚向后留出片段上限，仍不足下限时按下限取
    private fun effectiveDurationMs(track: MusicTrack, lines: List<LyricLine>): Long {
        if (track.duration > 0L) return track.duration
        val lastLine = lines.lastOrNull()?.timeMs ?: return 0L
        return lastLine + FALLBACK_TAIL_MS
    }

    private const val FALLBACK_TAIL_MS = 45_000L
}

/** 片段缓存的三态：未解析 / 已解析但不可定位 / 已解析出片段 */
internal sealed interface CacheState {
    data object Unknown : CacheState
    data object Miss : CacheState
    data class Found(val highlight: Highlight) : CacheState
}
