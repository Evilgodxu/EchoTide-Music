package com.yichao.evilgodxu.data.music.recommend

import android.content.Context
import com.yichao.evilgodxu.data.music.blacklist.BlacklistStore
import com.yichao.evilgodxu.data.music.model.MusicTrack
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 回忆模式：在本地曲库里找出「像你在听的、但你几乎忘了的」歌。
 *
 * 与已归档的每日推荐共用同一套打分内核（[LyricFeatures] + [TfIdf]），差别只在候选池与画像来源：
 * 候选从「各平台当期榜单」换成**本地全库中的尘封曲**，画像从「收藏」换成**常听为主、收藏补足**。
 * 于是整条链路不含任何网络请求。
 *
 * 关键是「不按播放次数排序，而按播放次数发资格」：
 *
 * - **粗筛**用播放次数 —— 只保留累计播放低于 [DUSTY_MAX_PLAYS] 的曲目。播放次数在这里是资格线，
 *   不是分数。
 * - **精排**用相似度 —— 在心里像你的那批里取最像的。**像且少听**才是尘封的好歌，
 *   光「少听」会把一堆你根本不喜欢的歌推上来。
 *
 * 相似度画像取常听为主、收藏补足：常听的语义是「你最近在听什么」，正是「回忆」要对齐的对象；
 * 收藏多是「存着以后听」的存量，单独用会让结果退化成「找你库里像你收藏的歌」。
 */
internal object MemoryRecommender {

    /** 一次展示的条数：翻页以此为步长 */
    const val PAGE_SIZE = 15

    /**
     * 启用门槛：累计计数覆盖的曲目数不足时，「没听过」与「数据还没攒够」无法区分，
     * 此时推荐会把用户当下在听的歌也当成尘封曲推出来，故不启用。
     */
    const val MIN_TRACKED_TRACKS = 30

    /** 启用门槛：画像样本的下限，收藏太少时画像不稳 */
    const val MIN_FAVORITES = 8

    // 三通道权重：概念通道最高，跨语言召回只有它能命中（与每日推荐同一组口径）
    private const val W_SURFACE = 0.35
    private const val W_CONCEPT = 0.45
    private const val W_RHYTHM = 0.20

    // 多样性惩罚：越大结果越分散
    private const val MMR_LAMBDA = 0.15

    // MMR 候选广度：多样性只在这批候选内取舍
    private const val MMR_POOL_LIMIT = 120

    // 计入「尘封」的累计播放上限：播过 0 或 1 次仍算尘封，两次以上已进入日常轮转
    private const val DUSTY_MAX_PLAYS = 1

    // 常听样本与收藏补足样本的画像权重：常听反映当下口味，权重更高
    private const val PRIMARY_WEIGHT = 1.0
    private const val SUPPLEMENT_WEIGHT = 0.5

    /**
     * 单轮参与打分的候选上限。
     *
     * 概念槽提取逐行对上千条词条做匹配，全库数千首一次算完会让首次进入明显卡顿；
     * 故先按「最尘封优先」取前若干首参与精排。上限之外的高分曲只是本次不参与，
     * 特征有缓存，下一轮或曲库变化后仍有入选机会。
     */
    private const val MAX_CANDIDATES = 400

    // 指称代词概念高频出现但无主题区分力，剔除后概念向量更能反映题材
    private val CONCEPT_DROP = setOf("you")

    private const val EPSILON = 1e-6

    /** 是否已达到启用条件：计数覆盖与收藏数同时达标 */
    fun isUnlocked(trackedTracks: Int, favoriteCount: Int): Boolean =
        trackedTracks >= MIN_TRACKED_TRACKS && favoriteCount >= MIN_FAVORITES

    /**
     * 推荐结果：曲目连同其概念特征。
     *
     * 特征随结果一并带出，供调用方在用户切走该曲时记一次跳过反馈（对同类内容降权）——
     * 特征只在打分内部产生，不随结果带出就得重算一次。
     */
    data class Pick(val track: MusicTrack, val features: Set<String>)

    /**
     * 对本地曲库排序，输出完整名次（展示时按 [PAGE_SIZE] 分页）。
     *
     * @param recentPlayedIds 常听（3 天内播够 2 次），既是画像主样本，也从候选中排除
     * @param likedIds 收藏，画像补足样本
     * @param playCounts 累计播放次数，尘封粗筛的依据
     */
    suspend fun rank(
        context: Context,
        library: List<MusicTrack>,
        recentPlayedIds: List<Long>,
        likedIds: Set<Long>,
        playCounts: Map<Long, Int>,
    ): List<Pick> = withContext(Dispatchers.Default) {
        if (library.isEmpty()) return@withContext emptyList()
        val byId = library.associateBy { it.id }
        val recentSet = recentPlayedIds.toSet()

        // ---------- 1. 画像样本：常听为主，收藏补足 ----------
        val primary = recentPlayedIds.mapNotNull { byId[it] }
        val supplement = likedIds.filterNot { it in recentSet }.mapNotNull { byId[it] }
        val samples = (primary + supplement).mapNotNull { track ->
            LyricFeatureCache.featuresOf(context, track)?.let { track to it }
        }
        if (samples.isEmpty()) return@withContext emptyList()
        val sampleWeights = samples.map { (track, _) ->
            if (track.id in recentSet) PRIMARY_WEIGHT else SUPPLEMENT_WEIGHT
        }

        // ---------- 2. 尘封粗筛：播放次数只作资格线，不作排序依据 ----------
        val sampleIds = samples.mapTo(HashSet()) { it.first.id }
        val blocked = BlacklistStore.keys
        val eligible = library.asSequence()
            .filter { track ->
                track.id !in sampleIds &&
                    track.id !in recentSet &&
                    (playCounts[track.id] ?: 0) <= DUSTY_MAX_PLAYS &&
                    !MusicBlacklist.isBlocked(blocked, track.title, track.artist)
            }
            // 最尘封优先：先按播放次数，再按入库时间。无入库时间的（在线曲目）排在同类之后
            .sortedWith(
                compareBy(
                    { playCounts[it.id] ?: 0 },
                    { it.fileModifiedMs.takeIf { ms -> ms > 0L } ?: Long.MAX_VALUE },
                )
            )
            .take(MAX_CANDIDATES)
            .toList()
        if (eligible.isEmpty()) return@withContext emptyList()

        // ---------- 3. 候选特征：无歌词的曲目无从比较，直接排除而非给低分 ----------
        val candidates = eligible.mapNotNull { track ->
            LyricFeatureCache.featuresOf(context, track)?.let { track to it }
        }
        if (candidates.isEmpty()) return@withContext emptyList()

        // ---------- 4. IDF 在（样本 ∪ 候选）上统计 ----------
        val sampleTerms = samples.map { it.second.terms }
        val sampleConcepts = samples.map { it.second.concepts }
        val candidateTerms = candidates.map { it.second.terms }
        val candidateConcepts = candidates.map { it.second.concepts }

        val termIdf = TfIdf.buildIdf(sampleTerms + candidateTerms)
        val conceptIdf = TfIdf.buildIdf(sampleConcepts + candidateConcepts)

        val sampleTermVectors = sampleTerms.map { TfIdf.vector(it, termIdf) }
        val candidateTermVectors = candidateTerms.map { TfIdf.vector(it, termIdf) }
        val sampleConceptVectors = sampleConcepts.map { TfIdf.vector(it, conceptIdf, CONCEPT_DROP) }
        val candidateConceptVectors = candidateConcepts.map { TfIdf.vector(it, conceptIdf, CONCEPT_DROP) }

        // 用户画像：样本向量的加权质心
        val userTerms = TfIdf.centroid(sampleTermVectors, sampleWeights)
        val userConcepts = TfIdf.centroid(sampleConceptVectors, sampleWeights)

        // ---------- 5. 黑名单算法（软降权）：过代表特征 + 用户跳过的特征 ----------
        val overRepresented = MusicBlacklist.overRepresented(candidateConceptVectors.map { it.keys })
        val skippedFeatures = BlacklistStore.skippedFeatures

        // ---------- 6. 节奏通道：候选结构到画像的标准化距离 ----------
        val sampleStructures = samples.map { it.second.structure }
        val candidateStructures = candidates.map { it.second.structure }
        val structureProfile = meanStructure(sampleStructures)
        val sigma = structureSigma(candidateStructures)
        val rhythmScores = candidateStructures.map { 1.0 / (1.0 + LyricFeatures.normalizedDistance(it, structureProfile, sigma)) }

        // ---------- 7. 打分 ----------
        val scored = candidates.mapIndexed { index, (track, _) ->
            Scored(
                track = track,
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
        selectDiverse(scored.sortedByDescending { it.score }.take(MMR_POOL_LIMIT), scored.size)
            .map { Pick(it.track, it.conceptVector.keys) }
    }

    // 结构画像：样本结构向量的均值，代表用户长期偏好的歌词结构
    private fun meanStructure(structures: List<StructureFeatures>): StructureFeatures {
        if (structures.isEmpty()) return StructureFeatures(0, 0.0, 0.0, 0.0)
        return StructureFeatures(
            lines = structures.sumOf { it.lines } / structures.size,
            avgLineLength = structures.sumOf { it.avgLineLength } / structures.size,
            chorusRepeat = structures.sumOf { it.chorusRepeat } / structures.size,
            uniqueRatio = structures.sumOf { it.uniqueRatio } / structures.size,
        )
    }

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
    private fun selectDiverse(scored: List<Scored>, limit: Int): List<Scored> {
        val pool = scored.toMutableList()
        val picked = mutableListOf<Scored>()
        val target = minOf(limit, pool.size)
        while (pool.isNotEmpty() && picked.size < target) {
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

    private data class Scored(
        val track: MusicTrack,
        val score: Double,
        val conceptVector: Map<String, Double>,
    )
}
