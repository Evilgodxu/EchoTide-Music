package com.yichao.evilgodxu.data.music.recommend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * TF-IDF 与余弦相似度复核。
 *
 * 推荐的三通道都建立在这组向量运算上：向量未归一化会让长歌词天然占优，
 * IDF 只在样本上统计则候选之间失去区分度。
 */
class TfIdfTest {

    private companion object {
        const val EPS = 1e-9
    }

    // -----------------------------------------------------------------------
    // IDF
    // -----------------------------------------------------------------------

    @Test
    fun idfDecreasesWithDocumentFrequency() {
        val idf = TfIdf.buildIdf(listOf(mapOf("爱" to 1, "夜" to 2), mapOf("爱" to 3)))
        // 「爱」出现在全部 2 篇中，「夜」只出现 1 篇，后者区分度更高
        assertTrue(idf.getValue("夜") > idf.getValue("爱"))
        // 文档数 2、频率 2 → ln(3/3) + 1
        assertEquals(1.0, idf.getValue("爱"), EPS)
    }

    @Test
    fun idfUsesSmoothedFormula() {
        val idf = TfIdf.buildIdf(listOf(mapOf("夜" to 1), mapOf("光" to 1)))
        // 文档数 2、频率 1 → ln(3/2) + 1
        assertEquals(ln(1.5) + 1.0, idf.getValue("夜"), EPS)
    }

    // -----------------------------------------------------------------------
    // 向量
    // -----------------------------------------------------------------------

    @Test
    fun vectorIsUnitLength() {
        val vector = TfIdf.vector(mapOf("爱" to 2, "夜" to 1), mapOf("爱" to 1.0, "夜" to 2.0))
        assertEquals(1.0, sqrt(vector.values.sumOf { it * it }), 1e-9)
    }

    @Test
    fun vectorUsesSublinearTermFrequency() {
        // TF 取 1+ln(tf)：线性 TF 会让「你」这类高频指称词淹没主题概念。
        // 归一化后绝对量无意义，故断言两词的权重比 —— 线性 TF 下该比值应为 4
        val vector = TfIdf.vector(mapOf("夜" to 1, "爱" to 4), emptyMap())
        assertEquals(1.0 + ln(4.0), vector.getValue("爱") / vector.getValue("夜"), EPS)
    }

    @Test
    fun vectorAppliesDropSetAndDefaultIdf() {
        // drop 命中项整项剔除；未登录词取默认 IDF 1.0 而非 0，否则新词永无权重
        val vector = TfIdf.vector(mapOf("爱" to 2, "你" to 5, "夜" to 1), emptyMap(), drop = setOf("你"))
        assertEquals(setOf("爱", "夜"), vector.keys)
        assertEquals(1.0 + ln(2.0), vector.getValue("爱") / vector.getValue("夜"), EPS)
    }

    @Test
    fun vectorRejectsNonPositiveCountsAndEmptyInput() {
        assertTrue(TfIdf.vector(mapOf("爱" to 0), emptyMap()).isEmpty())
        assertTrue(TfIdf.vector(emptyMap(), emptyMap()).isEmpty())
    }

    // -----------------------------------------------------------------------
    // 相似度与质心
    // -----------------------------------------------------------------------

    @Test
    fun cosineOfIdenticalVectorsIsOne() {
        val vector = TfIdf.vector(mapOf("爱" to 1, "夜" to 1), mapOf("爱" to 1.0, "夜" to 1.0))
        assertEquals(1.0, TfIdf.cosine(vector, vector), 1e-9)
    }

    @Test
    fun cosineOfDisjointVectorsIsZero() {
        val idf = mapOf("爱" to 1.0, "夜" to 1.0)
        val a = TfIdf.vector(mapOf("爱" to 1), idf)
        val b = TfIdf.vector(mapOf("夜" to 1), idf)
        assertEquals(0.0, TfIdf.cosine(a, b), EPS)
    }

    @Test
    fun cosineOfEmptyVectorIsZero() {
        val vector = TfIdf.vector(mapOf("爱" to 1), emptyMap())
        assertEquals(0.0, TfIdf.cosine(vector, emptyMap()), EPS)
        assertEquals(0.0, TfIdf.cosine(emptyMap(), vector), EPS)
    }

    @Test
    fun centroidIsWeightedAndNormalized() {
        val centroid = TfIdf.centroid(
            listOf(mapOf("爱" to 1.0), mapOf("夜" to 1.0)),
            listOf(3.0, 1.0),
        )
        // 权重 3:1 → 分量 0.75 / 0.25，再归一化
        assertEquals(0.75 / kotlin.math.hypot(0.75, 0.25), centroid.getValue("爱"), 1e-9)
        assertEquals(0.25 / kotlin.math.hypot(0.75, 0.25), centroid.getValue("夜"), 1e-9)
    }

    @Test
    fun centroidIsEmptyWithoutPositiveWeight() {
        assertTrue(TfIdf.centroid(emptyList(), emptyList()).isEmpty())
        assertTrue(TfIdf.centroid(listOf(mapOf("爱" to 1.0)), listOf(0.0)).isEmpty())
    }
}
