package com.yichao.evilgodxu.data.music.highlight

import android.content.Context
import com.yichao.evilgodxu.data.music.metadata.MusicMetadataCache
import com.yichao.evilgodxu.data.music.model.LyricLine
import com.yichao.evilgodxu.data.music.model.MusicTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 全库片段扫描：把每首歌的副歌区间算出来交给 [HighlightStore] 落盘。
 *
 * 只在后台成批进行，**不参与播放路径**。播放时只查表，于是切歌与切模式都不再读盘、不再定位，
 * 也就不会出现「片段何时生效取决于歌词何时就绪」的不确定行为。
 *
 * 只处理指纹过期的曲目（未扫过或歌词已变），重复调用无代价；歌词此刻不可得的曲目记为
 * [HighlightEntry.NoLyrics] 而非「无副歌」，待歌词补齐后指纹改变会被重扫。
 */
internal object HighlightScanner {

    // 分批落盘的批大小：整库扫描会产生成百上千条更新，逐条写盘既慢又频繁。
    // 偏大取值是有意的 —— 每次落盘都要重写整张表，崩溃时损失的这一批会在下次扫描重算，代价很小
    private const val CHECKPOINT = 200

    // 时长缺失时的兜底：以末行歌词为锚向后留出片段上限
    private const val FALLBACK_TAIL_MS = 45_000L

    /**
     * 扫描全库，返回是否产生了新的判定结果。
     *
     * 返回值的用途：为 true 说明片段表变了，调用方须同步已装载的播放队列（那里可能还是整曲项）；
     * 为 false 说明表本来就是最新的，什么也不必做 —— 大多数调用都会走到这一支。
     */
    suspend fun scan(context: Context, tracks: List<MusicTrack>): Boolean {
        HighlightStore.ensureLoaded(context)
        val pending = withContext(Dispatchers.Default) {
            tracks.filterNot { HighlightStore.isUpToDate(it) }
        }
        if (pending.isEmpty()) return false
        val batch = LinkedHashMap<Long, Pair<String, HighlightEntry>>()
        withContext(Dispatchers.Default) {
            pending.forEach { track ->
                batch[track.id] = resolve(context, track)
                if (batch.size >= CHECKPOINT) {
                    HighlightStore.commit(context, batch)
                    batch.clear()
                }
            }
        }
        if (batch.isNotEmpty()) HighlightStore.commit(context, batch)
        return true
    }

    // 解析单曲：读歌词 → 定位，结果连同歌词指纹一并返回（指纹决定这条结果何时过期）
    private suspend fun resolve(context: Context, track: MusicTrack): Pair<String, HighlightEntry> {
        val fingerprint = HighlightStore.fingerprintOf(track)
        val lines = withContext(Dispatchers.IO) { lyricLines(context, track) }
        if (lines.isEmpty()) return fingerprint to HighlightEntry.NoLyrics
        val highlight = HighlightLocator.locate(lines, effectiveDurationMs(track, lines))
        return fingerprint to (highlight?.let { HighlightEntry.Segment(it) } ?: HighlightEntry.NoChorus)
    }

    /**
     * 取曲目歌词：内存 → 曲目记录的缓存路径 → 按「歌名 - 艺术家」查找。
     *
     * 最后一步是必要的：曲目的歌词缓存路径不随播放列表落盘，冷启动后要到后台补全跑过才有值，
     * 只认它会让扫描在补全完成前把整库记成「无歌词」。
     */
    private fun lyricLines(context: Context, track: MusicTrack): List<LyricLine> {
        if (track.lyricLines.isNotEmpty()) return track.lyricLines
        val path = track.lyricCachePath
        if (MusicMetadataCache.isValid(path)) return MusicMetadataCache.loadLyrics(path)
        val byName = MusicMetadataCache.findLyrics(context, track.title, track.artist)
        return byName?.let { MusicMetadataCache.loadLyrics(it) }.orEmpty()
    }

    // 时长缺失时的兜底：以末行歌词为锚向后留出片段上限，仍不足下限时按下限取
    private fun effectiveDurationMs(track: MusicTrack, lines: List<LyricLine>): Long {
        if (track.duration > 0L) return track.duration
        val lastLine = lines.lastOrNull()?.timeMs ?: return 0L
        return lastLine + FALLBACK_TAIL_MS
    }
}
