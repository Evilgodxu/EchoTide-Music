package com.yichao.evilgodxu.data.music.recommend

import android.content.Context
import com.yichao.evilgodxu.data.music.metadata.MusicMetadataCache
import com.yichao.evilgodxu.data.music.model.MusicTrack
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 歌词特征缓存。
 *
 * 概念槽提取要逐行对上千条词条做包含匹配，代价集中在「行数 × 词条数」。每日推荐时代候选池
 * 只有约四百首且一轮日更只算一次，直接算尚可；回忆模式的候选是**本地全库**，且用户每次进入
 * 都要重排一次，逐轮全量重算会在曲库变大后明显卡顿。
 *
 * 故把「读歌词 + 提特征」收敛到本类并缓存：命中即复用，歌词未变则永不重算。
 * 缓存键带歌词指纹，曲目歌词被后台补全或手动刷新后指纹改变，旧特征作废重算 ——
 * 否则画像与候选会一直基于「当时还没有歌词」的空特征。
 */
internal object LyricFeatureCache {

    /** 单曲的歌词特征：三个通道的原始计数，TF-IDF 加权在排序时统一进行 */
    class Features(
        val terms: Map<String, Int>,
        val concepts: Map<String, Int>,
        val structure: StructureFeatures,
    )

    private class Entry(val fingerprint: String, val features: Features)

    private val cache = ConcurrentHashMap<Long, Entry>()

    /** 歌词指纹：行数与首末时间戳足以识别歌词是否被替换 */
    fun fingerprint(track: MusicTrack): String {
        val lines = track.lyricLines
        if (lines.isEmpty()) return "none:${track.lyricCachePath}"
        return "${lines.size}:${lines.first().timeMs}:${lines.last().timeMs}"
    }

    /**
     * 取单曲特征，命中缓存直接返回。
     *
     * 歌词优先取内存（后台补全通常已挂载），其次读歌词缓存文件 —— 读盘在 IO 线程进行。
     * 无有效歌词时返回 null，调用方据此把该曲排除出候选而不给低分：无歌词无从参与相似度比较。
     */
    suspend fun featuresOf(context: Context, track: MusicTrack): Features? {
        val fingerprint = fingerprint(track)
        cache[track.id]?.takeIf { it.fingerprint == fingerprint }?.let { return it.features }
        val lines = withContext(Dispatchers.IO) { LyricFeatures.cleanLyrics(rawLyricLines(context, track)) }
        if (lines.isEmpty()) return null
        val features = Features(
            terms = LyricFeatures.termCounts(lines),
            concepts = LyricFeatures.conceptCounts(lines),
            structure = LyricFeatures.structure(lines),
        )
        cache[track.id] = Entry(fingerprint, features)
        return features
    }

    private fun rawLyricLines(context: Context, track: MusicTrack): List<String> {
        if (track.lyricLines.isNotEmpty()) return track.lyricLines.map { it.text }
        val path = track.lyricCachePath
        if (MusicMetadataCache.isValid(path)) return MusicMetadataCache.loadLyrics(path).map { it.text }
        // 曲目的歌词缓存路径不随播放列表落盘，冷启动后要到后台补全跑过才有值。
        // 此处按「歌名 - 艺术家」再找一次，与元数据补全同一套定位规则，
        // 落盘过的 .lrc 因此在补全完成前也能参与画像与候选特征
        val byName = MusicMetadataCache.findLyrics(context, track.title, track.artist)
        return byName?.let { MusicMetadataCache.loadLyrics(it) }?.map { it.text }.orEmpty()
    }
}
