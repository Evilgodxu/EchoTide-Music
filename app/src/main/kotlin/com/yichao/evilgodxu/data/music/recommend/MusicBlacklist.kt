package com.yichao.evilgodxu.data.music.recommend

import com.yichao.evilgodxu.data.music.blacklist.BlacklistStore

/**
 * 黑名单算法：硬过滤、软降权与逆向反馈三段。
 *
 * 黑名单并不只是「把拉黑的歌剔掉」——被明确拉黑的对象在粗排阶段直接跳过，
 * 而候选集中过度集中的特征、以及用户跳过的推荐曲目特征，则以降权方式参与排序，
 * 二者共同抑制推荐结果同质化。降权对象是「特征」而非歌曲本身，因此一次拉黑或一次跳过
 * 影响的是同类内容，不会退化成逐曲过滤。
 *
 * 三段的输入都由 [com.yichao.evilgodxu.data.music.blacklist.BlacklistStore] 持久化，
 * 没有仅本次进程有效的部分。
 */
internal object MusicBlacklist {

    // 单条过代表特征与单次跳过反馈的降权幅度
    private const val OVER_REPRESENTED_STEP = 0.04
    private const val SKIP_STEP = 0.03

    // 两类软惩罚各自的封顶：避免单个特征命中过多就把歌曲一票否决，排序仍由三通道主导
    private const val MAX_OVER_REPRESENTED_PENALTY = 0.12
    private const val MAX_SKIP_PENALTY = 0.15

    // 候选集中出现比例达到该值的特征视为过代表：半数以上候选共有即无区分度，且易致同质化
    private const val OVER_REPRESENTED_RATIO = 0.5

    // 候选数过少时文档频率统计不稳定，不启用软黑名单
    private const val MIN_CANDIDATES_FOR_SOFT_BLACKLIST = 4

    /** 硬过滤：命中用户拉黑歌曲的候选在粗排阶段直接跳过 */
    fun isBlocked(blockedSongs: Set<String>, title: String, artist: String): Boolean =
        BlacklistStore.keyOf(title, artist) in blockedSongs

    /**
     * 软降权总量：过代表特征与用户跳过的特征分别累加后各自封顶。
     *
     * @param features 候选曲目的特征（概念槽）
     * @param overRepresented 候选集内过度集中的特征
     * @param skippedFeatures 被跳过的推荐曲目特征及命中次数
     */
    fun penalty(
        features: Set<String>,
        overRepresented: Set<String>,
        skippedFeatures: Map<String, Int>,
    ): Double {
        if (features.isEmpty()) return 0.0
        val overRepresentedPenalty =
            (features.count { it in overRepresented } * OVER_REPRESENTED_STEP)
                .coerceAtMost(MAX_OVER_REPRESENTED_PENALTY)
        val skipPenalty =
            (features.sumOf { skippedFeatures[it] ?: 0 } * SKIP_STEP)
                .coerceAtMost(MAX_SKIP_PENALTY)
        return overRepresentedPenalty + skipPenalty
    }

    /** 统计候选集内过度集中的特征：这批候选共有的题材会被压分，让结果更分散 */
    fun overRepresented(candidateFeatures: List<Set<String>>): Set<String> {
        if (candidateFeatures.size < MIN_CANDIDATES_FOR_SOFT_BLACKLIST) return emptySet()
        val threshold = candidateFeatures.size * OVER_REPRESENTED_RATIO
        val frequency = mutableMapOf<String, Int>()
        candidateFeatures.forEach { features ->
            features.forEach { frequency[it] = (frequency[it] ?: 0) + 1 }
        }
        return frequency.filterValues { it >= threshold }.keys
    }
}