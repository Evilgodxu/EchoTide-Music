package com.yichao.evilgodxu.data.music.recommend

import android.content.Context
import com.yichao.evilgodxu.data.music.blacklist.BlacklistStore
import com.yichao.evilgodxu.data.music.metadata.MusicMetadataCache
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.model.NeteaseSongSearchResult
import com.yichao.evilgodxu.data.music.model.OwnedTrackIndex
import com.yichao.evilgodxu.data.music.model.playlistTrackId
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 每日推荐：本地歌词偏好 → 榜单候选池 → 黑名单算法 → 三通道打分 → 多样性重排。
 *
 * 候选池取自各内置平台的榜单（见 [ChartPool]）而非搜索结果：搜索只能召回用户已经想到的歌，
 * 榜单提供与用户历史无关的当期新歌，这是"每日推荐"区别于"搜索"的前提。
 * 候选池已做日更落盘，本类全程不再发起网络请求。
 *
 * 输出当日的 [DAILY_RECOMMEND_COUNT] 首。候选池每日换期，换期后重算即在当日候选上得到一份新排序，
 * 取榜首几首即当日结果 —— 次序不再按天向下推进，跨天的新旧差异由候选池换血带来。
 */
internal object MusicRecommender {

    /** 每日展示的推荐条数：换期后取排序榜首的这几首 */
    private const val DAILY_RECOMMEND_COUNT = 5

    // 三通道权重：概念通道权重最高，跨语言召回只有它能命中
    private const val W_SURFACE = 0.30
    private const val W_CONCEPT = 0.45
    private const val W_RHYTHM = 0.25

    // 多样性惩罚：越大结果越分散，避免推荐清一色同题材
    private const val MMR_LAMBDA = 0.15

    // MMR 候选广度：多样性重排只在同一批候选内做取舍，候选面不宽于展示条数时它退化为按分数取前几首。
    // 广度即多样性预算 —— 面外的高分曲目再像也不会被换下，故取到 120 首，远超每日展示条数
    private const val MMR_POOL_LIMIT = 120

    // 指称代词概念高频出现但无主题区分力，剔除后概念向量更能反映题材
    private val CONCEPT_DROP = setOf("you")

    /**
     * 生成当日推荐。
     *
     * @param tracks 本地偏好基线（收藏曲目），歌词取自曲目内嵌内容或歌词缓存文件
     * @param library 本地全量曲库，仅用于把用户已拥有的歌排除出候选
     *
     * 只读本地候选池：换期刷新与启动预热由调用方在计算前统一确保。本类全程不发起网络请求 ——
     * 排序计算会被收藏变更、曲库入库等高频操作取消重来，整池抓取放在这里会被连点一并丢弃。
     */
    suspend fun recommend(
        context: Context,
        tracks: List<MusicTrack>,
        library: List<MusicTrack>,
    ): RecommendationResult = withContext(Dispatchers.IO) {
        // ---------- 1. 本地偏好基线提取 ----------
        val samples = tracks.mapNotNull { track ->
            LyricFeatures.cleanLyrics(sampleLyricLines(track)).takeIf { it.isNotEmpty() }
        }
        if (samples.isEmpty()) return@withContext RecommendationResult(emptyList(), 0L)

        val sampleTerms = samples.map { LyricFeatures.termCounts(it) }
        val sampleConcepts = samples.map { LyricFeatures.conceptCounts(it) }
        val sampleStructures = samples.map { LyricFeatures.structure(it) }

        // ---------- 2. 候选池：只读落盘结果（联网更新由调用方在计算前确保），
        // 本地已有的歌在此排除，与黑名单无关。排除依据是全量曲库而非收藏 ——
        // 缓存/下载入库的歌大多未被收藏，只按收藏排除会让用户已经拥有的歌继续占着推荐位
        val pool = ChartPool.snapshot(context, refresh = false)
        val owned = OwnedTrackIndex(library)
        val candidates = pool.items.filterNot { owned.contains(it.result.title, it.result.artist) }
        if (candidates.isEmpty()) return@withContext RecommendationResult(emptyList(), pool.fetchedAt)

        // ---------- 3. 黑名单算法（硬过滤）：拉黑对象在粗排阶段直接跳过 ----------
        val blockedSongs = BlacklistStore.keys
        val survivors = candidates.filterNot {
            MusicBlacklist.isBlocked(blockedSongs, it.result.title, it.result.artist)
        }
        if (survivors.isEmpty()) return@withContext RecommendationResult(emptyList(), pool.fetchedAt)

        // ---------- 4. IDF 在（样本 ∪ 候选）上统计 ----------
        val candidateTerms = survivors.map { LyricFeatures.termCounts(it.lines) }
        val candidateConcepts = survivors.map { LyricFeatures.conceptCounts(it.lines) }

        val termIdf = TfIdf.buildIdf(sampleTerms + candidateTerms)
        val conceptIdf = TfIdf.buildIdf(sampleConcepts + candidateConcepts)

        val sampleTermVectors = sampleTerms.map { TfIdf.vector(it, termIdf) }
        val candidateTermVectors = candidateTerms.map { TfIdf.vector(it, termIdf) }
        val sampleConceptVectors = sampleConcepts.map { TfIdf.vector(it, conceptIdf, CONCEPT_DROP) }
        val candidateConceptVectors = candidateConcepts.map { TfIdf.vector(it, conceptIdf, CONCEPT_DROP) }

        // 用户画像：样本向量的等权质心
        val userTerms = TfIdf.centroid(sampleTermVectors, List(samples.size) { 1.0 })
        val userConcepts = TfIdf.centroid(sampleConceptVectors, List(samples.size) { 1.0 })

        // ---------- 5. 黑名单算法（软降权）：过代表特征 + 用户跳过的特征 ----------
        val overRepresented = MusicBlacklist.overRepresented(candidateConceptVectors.map { it.keys })
        val skippedFeatures = BlacklistStore.skippedFeatures

        // ---------- 6. 节奏通道：候选结构到画像的标准化距离 ----------
        val structures = survivors.map { LyricFeatures.structure(it.lines) }
        val profile = structureProfile(structures)
        val sigma = structureSigma(structures)
        val distances = structures.map { LyricFeatures.normalizedDistance(it, profile, sigma) }
        // 距离越小得分越高，用 1/(1+d) 映射为绝对相似度，而非按本批距离极值 min-max 归一：
        // min-max 会让节奏分数恒占满 [0,1]，同一条曲目的得分随候选池换血而漂移，
        // 且与词面/概念（绝对余弦）不同量纲，使名义权重的含义失真
        val rhythmScores = distances.map { 1.0 / (1.0 + it) }

        // ---------- 7. 打分 ----------
        val scored = survivors.mapIndexed { index, candidate ->
            Scored(
                result = candidate.result,
                score = W_SURFACE * TfIdf.cosine(userTerms, candidateTermVectors[index]) +
                    W_CONCEPT * TfIdf.cosine(userConcepts, candidateConceptVectors[index]) +
                    W_RHYTHM * rhythmScores[index] -
                    MusicBlacklist.penalty(
                        candidateConceptVectors[index].keys,
                        overRepresented,
                        skippedFeatures,
                    ),
                conceptVector = candidateConceptVectors[index],
            )
        }

        // ---------- 8. MMR 多样性重排 ----------
        // 先在得分的降序上截出候选面，再在其上贪心挑出当日结果：多样性只能在同一批候选内取舍，
        // 直接按分数取前几首会让同一题材占满展示位
        val scoreRanked = scored.sortedByDescending { it.score }.take(MMR_POOL_LIMIT)
        val picks = selectDiverse(scoreRanked, DAILY_RECOMMEND_COUNT)
            .map { RecommendedSong(it.result, it.conceptVector.keys) }
        RecommendationResult(picks, pool.fetchedAt)
    }

    /** 样本歌词：优先取已加载到内存的歌词，其次读歌词缓存文件 */
    private fun sampleLyricLines(track: MusicTrack): List<String> {
        if (track.lyricLines.isNotEmpty()) return track.lyricLines.map { it.text }
        val path = track.lyricCachePath
        if (!MusicMetadataCache.isValid(path)) return emptyList()
        return MusicMetadataCache.loadLyrics(path).map { it.text }
    }

    // 结构画像：样本结构向量的均值，代表用户长期偏好的歌词结构
    private fun structureProfile(structures: List<StructureFeatures>): StructureFeatures = StructureFeatures(
        lines = structures.sumOf { it.lines } / structures.size,
        avgLineLength = structures.sumOf { it.avgLineLength } / structures.size,
        chorusRepeat = structures.sumOf { it.chorusRepeat } / structures.size,
        uniqueRatio = structures.sumOf { it.uniqueRatio } / structures.size,
    )

    // 尺度取候选池标准差：样本数量少时样本方差过小，会把距离整体放大到无区分度
    private fun structureSigma(structures: List<StructureFeatures>): StructureSigma {
        if (structures.isEmpty()) return StructureSigma(1.0, 1.0, 1.0, 1.0)
        return StructureSigma(
            lines = structures.map { it.lines.toDouble() }.std().coerceAtLeast(EPSILON),
            avgLineLength = structures.map { it.avgLineLength }.std().coerceAtLeast(EPSILON),
            chorusRepeat = structures.map { it.chorusRepeat }.std().coerceAtLeast(EPSILON),
            uniqueRatio = structures.map { it.uniqueRatio }.std().coerceAtLeast(EPSILON),
        )
    }

    /** 贪心 MMR：每次选「自身得分高且与已选结果差异大」的候选 */
    private fun selectDiverse(scored: List<Scored>, count: Int): List<Scored> {
        val pool = scored.toMutableList()
        val picked = mutableListOf<Scored>()
        while (pool.isNotEmpty() && picked.size < count) {
            var best: Scored? = null
            var bestValue = Double.NEGATIVE_INFINITY
            pool.forEach { candidate ->
                val redundancy = picked.maxOfOrNull {
                    TfIdf.cosine(candidate.conceptVector, it.conceptVector)
                } ?: 0.0
                val value = (1 - MMR_LAMBDA) * candidate.score - MMR_LAMBDA * redundancy
                if (value > bestValue) {
                    best = candidate
                    bestValue = value
                }
            }
            val chosen = best ?: break
            picked += chosen
            pool -= chosen
        }
        return picked
    }

    private fun List<Double>.std(): Double {
        if (isEmpty()) return 0.0
        val mean = sum() / size
        return sqrt(sumOf { (it - mean) * (it - mean) } / size)
    }

    private const val EPSILON = 1e-6

    private data class Scored(
        val result: NeteaseSongSearchResult,
        val score: Double,
        val conceptVector: Map<String, Double>,
    )
}

/**
 * 每日推荐结果。
 *
 * [recommendations] 是当日展示的推荐曲目，按打分经多样性重排后的名次排列。
 * [poolFetchedAt] 是本次结果所依据的候选池抓取时刻，调用方据此判定结果是否仍属当期。
 */
data class RecommendationResult(
    val recommendations: List<RecommendedSong>,
    val poolFetchedAt: Long,
)

/**
 * 每日推荐结果。
 *
 * [features] 是该曲目的概念特征，用户跳过该曲目时据此对同类特征降权（逆向反馈）。
 */
data class RecommendedSong(
    val result: NeteaseSongSearchResult,
    val features: Set<String>,
) {
    /** 该推荐曲目进入播放列表后的曲目 ID */
    val trackId: Long get() = result.playlistTrackId
}
