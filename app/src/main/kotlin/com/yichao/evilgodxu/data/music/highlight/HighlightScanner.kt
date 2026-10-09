package com.yichao.evilgodxu.data.music.highlight

import android.content.Context
import com.yichao.evilgodxu.data.music.analysis.EnergyEnvelopeDecoder
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
 * 扫描分两档，代价与精度对应：
 *
 * - **粗扫**（[scan] 的 audioRefinement 为 false）：只用歌词结构定位（[HighlightLocator]）。
 *   有清晰重复副歌的曲子由此直接定案，全程不解码音频，廉价。
 * - **精修**（audioRefinement 为 true）：对歌词判不了的曲子（副歌不重复、歌词无时间轴、
 *   候选并列）再解码一次音频能量包络（[EnergyEnvelopeDecoder]）交给 [HighlightSelector] 决断。
 *   进入心动模式时才做，于是从不使用该模式的用户永远不付解码代价。
 *
 * 只处理指纹过期的曲目（未扫过、歌词已变、或粗扫结果待精修），重复调用无代价。
 * 歌词此刻不可得的曲目记为 [HighlightEntry.Unresolved] 而非「无副歌」，待歌词补齐后指纹改变会被重扫。
 */
internal object HighlightScanner {

    // 分批落盘的批大小：整库扫描会产生成百上千条更新，逐条写盘既慢又频繁。
    // 偏大取值是有意的 —— 每次落盘都要重写整张表，崩溃时损失的这一批会在下次扫描重算，代价很小
    private const val CHECKPOINT = 200

    // 时长缺失时的兜底：以末行歌词为锚向后留出片段上限
    private const val FALLBACK_TAIL_MS = 45_000L

    // 歌词侧「足够决断」的重复次数：副歌反复三遍以上时音频不再提供额外信息，故不必解码
    private const val STRONG_REPEAT = 3

    /**
     * 扫描全库，返回是否产生了新的判定结果。
     *
     * 返回值的用途：为 true 说明片段表变了，调用方须同步已装载的播放队列（那里可能还是整曲项）；
     * 为 false 说明表本来就是最新的，什么也不必做 —— 大多数调用都会走到这一支。
     *
     * @param audioRefinement 是否允许解码音频做精修（进入心动模式时为 true）
     */
    suspend fun scan(context: Context, tracks: List<MusicTrack>, audioRefinement: Boolean): Boolean {
        HighlightStore.ensureLoaded(context)
        val pending = withContext(Dispatchers.Default) {
            tracks.filterNot { HighlightStore.isUpToDate(it, audioRefinement) }
        }
        if (pending.isEmpty()) return false
        val batch = LinkedHashMap<Long, HighlightStore.ScanResult>()
        withContext(Dispatchers.Default) {
            pending.forEach { track ->
                batch[track.id] = resolve(context, track, audioRefinement)
                if (batch.size >= CHECKPOINT) {
                    HighlightStore.commit(context, batch)
                    batch.clear()
                }
            }
        }
        if (batch.isNotEmpty()) HighlightStore.commit(context, batch)
        return true
    }

    /**
     * 解析单曲：歌词 → 候选 → （按需）音频 → 决策。
     *
     * 结果连同指纹与「是否已精修」一并返回 —— 后两者决定这条结果何时过期、何时只需粗扫即可复用。
     */
    private suspend fun resolve(
        context: Context,
        track: MusicTrack,
        audioRefinement: Boolean,
    ): HighlightStore.ScanResult {
        val fingerprint = HighlightStore.fingerprintOf(track)
        val lines = withContext(Dispatchers.IO) { lyricLines(context, track) }
        if (lines.isEmpty()) {
            // 无歌词无从定位，但也可能只是歌词尚未补齐：记无法定位（整曲播放），待补全后重扫。
            // 是否精修随本轮口径：粗扫留下未精修标记，进入模式后重扫；精修轮则不再重复读盘
            return HighlightStore.ScanResult(
                fingerprint = fingerprint,
                refined = audioRefinement,
                entry = HighlightEntry.Unresolved,
            )
        }
        val durationMs = effectiveDurationMs(track, lines)
        val candidates = HighlightLocator.candidates(lines, durationMs)
        val decisive = isLyricDecisive(candidates)
        if (decisive) {
            // 歌词侧已足够决断：音频不再提供额外信息，省下解码
            return HighlightStore.ScanResult(
                fingerprint = fingerprint,
                refined = true,
                entry = HighlightEntry.Segment(candidates.first().toHighlight()),
            )
        }

        // 歌词不足以决断：按需用音频能量补充。非本地源无法解码，直接沿用歌词侧结果
        val envelope = if (audioRefinement && track.isLocalAudioSource) {
            withContext(Dispatchers.IO) { EnergyEnvelopeDecoder.decode(context, track) }
        } else {
            null
        }
        val highlight = HighlightSelector.select(candidates, envelope, lines)
        val entry = when {
            highlight != null -> HighlightEntry.Segment(highlight)
            // 音频可用且有歌词时间轴却收不出片段：确无高潮，可跳过该曲
            envelope != null && HighlightLocator.hasUsableTimeline(lines) -> HighlightEntry.NoChorus
            // 其余（音频不可读、歌词无时间轴、本次未精修）一律整曲播放，避免「有歌词却漏播」
            else -> HighlightEntry.Unresolved
        }
        // refined 记「本轮已用尽可用的判据」：精修轮的音频能力已尝试过，粗扫轮则还没有，
        // 故粗扫留下的条目会在进入心动模式后被判过期而重算，精修条目不会被反复重算
        return HighlightStore.ScanResult(fingerprint, refined = audioRefinement, entry = entry)
    }

    // 歌词侧是否已足够决断：唯一候选且重复次数达到强证据阈值
    private fun isLyricDecisive(candidates: List<HighlightLocator.Candidate>): Boolean =
        candidates.size == 1 && candidates.first().repeats >= STRONG_REPEAT

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
