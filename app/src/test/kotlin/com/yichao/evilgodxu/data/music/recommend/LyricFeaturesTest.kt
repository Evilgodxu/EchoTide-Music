package com.yichao.evilgodxu.data.music.recommend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 歌词特征提取复核：多语分词、跨语概念映射与结构代理特征。
 *
 * 词面相似度在跨语言时交集恒为空（日文歌词与中文歌词毫无共同词元），
 * 概念槽是唯一可用的召回通道，故这里按语种分别验证其命中路径。
 */
class LyricFeaturesTest {

    private companion object {
        const val EPS = 1e-9
    }

    // -----------------------------------------------------------------------
    // 清洗
    // -----------------------------------------------------------------------

    @Test
    fun cleanLyricsDropsMetadataAndBlankLines() {
        val cleaned = LyricFeatures.cleanLyrics(listOf("作词：甲", "  ", "夜色真好", "制作人：乙"))
        assertEquals(listOf("夜色真好"), cleaned)
    }

    @Test
    fun cleanLyricsTrimsRemainingLines() {
        assertEquals(listOf("夜色真好"), LyricFeatures.cleanLyrics("  夜色真好  \n"))
    }

    // -----------------------------------------------------------------------
    // 分词
    // -----------------------------------------------------------------------

    @Test
    fun latinTokensAreLowercasedAndStopped() {
        assertEquals(listOf("hello", "world"), LyricFeatures.tokenize("Hello World the a"))
    }

    @Test
    fun chineseTokensAreCharacterBigrams() {
        // 2-gram 而非词典分词：歌词中新词与专有名词多，词表分词会把它们整段丢弃
        assertEquals(listOf("今天", "天天", "天气"), LyricFeatures.tokenize("今天天气"))
    }

    @Test
    fun chineseBigramsContainingStopCharactersAreDropped() {
        // 「词元含任一停用字即丢弃」：我的/们的这类组合不带主题信息
        assertTrue(LyricFeatures.tokenize("我的").isEmpty())
    }

    @Test
    fun kanaRunsMadeOnlyOfParticlesAreDropped() {
        // 逐字过滤会把「なみだ」这类实词一并杀掉，故整段二元组全为助词类假名才丢弃
        assertTrue(LyricFeatures.tokenize("のって").isEmpty())
        assertEquals(listOf("なみ", "みだ"), LyricFeatures.tokenize("なみだ"))
    }

    @Test
    fun koreanTokensAreStrippedOfEndings() {
        // 사랑 / 사랑을 / 사랑해요 在词面上是三个词元，不剥尾则同一词的不同格位互不相识
        assertEquals(listOf("사랑"), LyricFeatures.tokenize("사랑해요"))
        assertEquals(listOf("사랑"), LyricFeatures.tokenize("사랑을"))
    }

    @Test
    fun cyrillicTokensKeepWholeWords() {
        assertEquals(listOf("любовь"), LyricFeatures.tokenize("любовь и"))
    }

    @Test
    fun termCountsAccumulateAcrossLines() {
        val counts = LyricFeatures.termCounts(listOf("今天天气", "今天"))
        assertEquals(2, counts["今天"])
        assertEquals(1, counts["天天"])
        assertEquals(1, counts["天气"])
    }

    // -----------------------------------------------------------------------
    // 概念槽
    // -----------------------------------------------------------------------

    @Test
    fun chineseConceptsMatchByContainment() {
        assertEquals(1, LyricFeatures.conceptCounts(listOf("我爱你"))["love"])
        assertEquals(1, LyricFeatures.conceptCounts(listOf("我爱你"))["you"])
    }

    @Test
    fun longerScriptConceptWinsOverShorterOne() {
        // 汉字概念按长度倒序命中：否则「心」会先于「心疼」命中而丢失更具体的语义。
        // 二者同属 heart 槽，故用计数验证两条词条都被计入
        assertEquals(2, LyricFeatures.conceptCounts(listOf("心疼"))["heart"])
    }

    @Test
    fun koreanConceptsMatchByTokenPrefix() {
        assertEquals(1, LyricFeatures.conceptCounts(listOf("사랑해요"))["love"])
    }

    @Test
    fun englishConceptsMatchByWholeWord() {
        assertEquals(1, LyricFeatures.conceptCounts(listOf("love"))["love"])
    }

    @Test
    fun germanAndRussianConceptsMatchByStemSubstring() {
        // 德语靠复合构词、俄语按格变形，整词匹配基本落空，故以词干包含判据命中
        assertEquals(1, LyricFeatures.conceptCounts(listOf("Herzschlag"))["heart"])
        assertEquals(1, LyricFeatures.conceptCounts(listOf("любовь"))["love"])
    }

    // -----------------------------------------------------------------------
    // 结构特征
    // -----------------------------------------------------------------------

    @Test
    fun structureCountsRepeatedLinesAsChorus() {
        val features = LyricFeatures.structure(listOf("a", "b", "a"))
        assertEquals(3, features.lines)
        assertEquals(1.0, features.avgLineLength, EPS)
        assertEquals(1.0 - 2.0 / 3.0, features.chorusRepeat, EPS)
        assertEquals(2.0 / 3.0, features.uniqueRatio, EPS)
    }

    @Test
    fun structureOfEmptyLyricsIsZero() {
        val features = LyricFeatures.structure(emptyList())
        assertEquals(0, features.lines)
        assertEquals(0.0, features.avgLineLength, EPS)
    }

    @Test
    fun normalizedDistanceOfIdenticalFeaturesIsZero() {
        val features = LyricFeatures.structure(listOf("a", "b", "a"))
        val sigma = StructureSigma(1.0, 1.0, 1.0, 1.0)
        assertEquals(0.0, LyricFeatures.normalizedDistance(features, features, sigma), EPS)
    }

    @Test
    fun normalizedDistanceScalesEachDimensionBySigma() {
        val a = StructureFeatures(10, 0.0, 0.0, 0.0)
        val b = StructureFeatures(12, 0.0, 0.0, 0.0)
        val sigma = StructureSigma(2.0, 1.0, 1.0, 1.0)
        // 四维各自标准化后取均方：仅行数相差 2，缩放至 1 个标准差 → sqrt(1/4)
        assertEquals(0.5, LyricFeatures.normalizedDistance(a, b, sigma), EPS)
    }
}
