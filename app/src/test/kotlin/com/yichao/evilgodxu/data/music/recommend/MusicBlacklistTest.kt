package com.yichao.evilgodxu.data.music.recommend

import com.yichao.evilgodxu.data.music.blacklist.BlacklistStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 黑名单算法复核：硬过滤、软降权与过代表特征统计。
 *
 * 降权对象是「特征」而非歌曲本身，两类惩罚各自封顶，
 * 排序仍由三通道主导 —— 单个特征命中过多不应把歌曲一票否决。
 */
class MusicBlacklistTest {

    private companion object {
        const val EPS = 1e-9
    }

    // -----------------------------------------------------------------------
    // 硬过滤
    // -----------------------------------------------------------------------

    @Test
    fun blockedSongIsFilteredByNormalizedKey() {
        val blocked = setOf(BlacklistStore.keyOf("祝福祖国", "甲"))
        assertTrue(MusicBlacklist.isBlocked(blocked, "祝福祖国", "甲"))
        // 版本后缀在归一化后消失，拉黑意图不因平台写法差异而失效
        assertTrue(MusicBlacklist.isBlocked(blocked, "祝福祖国 (Live)", "甲"))
    }

    @Test
    fun differentArtistIsNotBlocked() {
        val blocked = setOf(BlacklistStore.keyOf("祝福祖国", "甲"))
        assertFalse(MusicBlacklist.isBlocked(blocked, "祝福祖国", "乙"))
    }

    // -----------------------------------------------------------------------
    // 软降权
    // -----------------------------------------------------------------------

    @Test
    fun penaltyIsZeroWithoutFeatures() {
        assertEquals(0.0, MusicBlacklist.penalty(emptySet(), setOf("love"), emptyMap()), EPS)
    }

    @Test
    fun overRepresentedPenaltyAccumulatesThenCaps() {
        val overRepresented = setOf("love", "night", "rain", "tears")
        assertEquals(0.04, MusicBlacklist.penalty(setOf("love"), overRepresented, emptyMap()), EPS)
        assertEquals(0.12, MusicBlacklist.penalty(setOf("love", "night", "rain"), overRepresented, emptyMap()), EPS)
        // 封顶 0.12：特征命中再多也不把歌曲一票否决
        assertEquals(0.12, MusicBlacklist.penalty(overRepresented, overRepresented, emptyMap()), EPS)
    }

    @Test
    fun skipPenaltyWeightsByHitCountThenCaps() {
        val features = setOf("love", "night")
        assertEquals(0.15, MusicBlacklist.penalty(features, emptySet(), mapOf("love" to 2, "night" to 3)), EPS)
        // 封顶 0.15：单次跳过的反馈不应累积成永久否决
        assertEquals(0.15, MusicBlacklist.penalty(features, emptySet(), mapOf("love" to 10)), EPS)
    }

    @Test
    fun bothPenaltiesAreSummed() {
        val features = setOf("love", "night", "rain")
        assertEquals(
            0.27,
            MusicBlacklist.penalty(features, features, mapOf("love" to 5)),
            EPS,
        )
    }

    // -----------------------------------------------------------------------
    // 过代表特征
    // -----------------------------------------------------------------------

    @Test
    fun tooFewCandidatesDisablesSoftBlacklist() {
        // 候选数过少时文档频率统计不稳定
        assertTrue(
            MusicBlacklist.overRepresented(listOf(setOf("love"), setOf("love"), setOf("love"))).isEmpty()
        )
    }

    @Test
    fun featuresSharedByHalfTheCandidatesAreOverRepresented() {
        val candidates = listOf(
            setOf("love", "night"),
            setOf("love", "rain"),
            setOf("night", "tears"),
            setOf("rain"),
        )
        // 4 个候选中出现 ≥2 次即视为无区分度；tears 仅 1 次，保留其区分能力
        assertEquals(setOf("love", "night", "rain"), MusicBlacklist.overRepresented(candidates))
    }

    @Test
    fun rareFeaturesAreNotOverRepresented() {
        val candidates = listOf(setOf("love"), setOf("night"), setOf("rain"), setOf("tears"))
        assertTrue(MusicBlacklist.overRepresented(candidates).isEmpty())
    }
}
