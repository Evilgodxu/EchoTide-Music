package com.yichao.evilgodxu.data.music.recommend

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * TF-IDF 向量与余弦相似度。
 *
 * IDF 在「偏好样本 ∪ 候选池」上统一统计：只在样本上统计会让候选池里的词退化为默认权重，
 * 无法体现候选之间的区分度。
 */
internal object TfIdf {

    /** 每首歌一个词频表，返回词 → IDF（平滑公式，未登录词取默认权重） */
    fun buildIdf(documents: List<Map<String, Int>>): Map<String, Double> {
        val documentCount = documents.size
        val docFrequency = mutableMapOf<String, Int>()
        documents.forEach { document ->
            document.keys.forEach { docFrequency[it] = (docFrequency[it] ?: 0) + 1 }
        }
        return docFrequency.mapValues { (_, frequency) ->
            ln((documentCount + 1).toDouble() / (frequency + 1)) + 1.0
        }
    }

    /**
     * 归一化 TF-IDF 向量。
     *
     * TF 取次线性 1+ln(tf)：线性 TF 会让「你 / you」这类高频指称词淹没主题概念。
     */
    fun vector(
        counts: Map<String, Int>,
        idf: Map<String, Double>,
        drop: Set<String> = emptySet(),
    ): Map<String, Double> {
        if (counts.isEmpty()) return emptyMap()
        val vector = mutableMapOf<String, Double>()
        counts.forEach { (term, count) ->
            if (term in drop || count <= 0) return@forEach
            vector[term] = (1.0 + ln(count.toDouble())) * (idf[term] ?: 1.0)
        }
        val norm = sqrt(vector.values.sumOf { it * it }).takeIf { it > 0.0 } ?: return emptyMap()
        return vector.mapValues { it.value / norm }
    }

    /** 余弦相似度：两个向量均已归一化，取共同维度的点积 */
    fun cosine(a: Map<String, Double>, b: Map<String, Double>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var sum = 0.0
        a.forEach { (term, weight) -> b[term]?.let { sum += weight * it } }
        return sum
    }

    /** 加权质心：样本向量按权重合成后归一化，作为用户画像 */
    fun centroid(vectors: List<Map<String, Double>>, weights: List<Double>): Map<String, Double> {
        if (vectors.isEmpty()) return emptyMap()
        val total = weights.sum().takeIf { it > 0.0 } ?: return emptyMap()
        val merged = mutableMapOf<String, Double>()
        vectors.forEachIndexed { index, vector ->
            val weight = weights[index] / total
            vector.forEach { (term, value) -> merged[term] = (merged[term] ?: 0.0) + value * weight }
        }
        val norm = sqrt(merged.values.sumOf { it * it }).takeIf { it > 0.0 } ?: return emptyMap()
        return merged.mapValues { it.value / norm }
    }
}