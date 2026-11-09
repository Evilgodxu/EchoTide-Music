package com.yichao.evilgodxu.data.music.api

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 歌词编解码复核：逐行 LRC、增强 LRC、QRC、KRC、酷我 lrcx 与译文合并。
 *
 * 各平台的字标签口径不一（QRC 给绝对时间、KRC 与 lrcx 给相对偏移），
 * 测试断言的是归一后的绝对毫秒，故同一句歌词在这三种格式下应得到一致的字时间轴。
 */
class LyricCodecTest {

    // -----------------------------------------------------------------------
    // 逐行 LRC
    // -----------------------------------------------------------------------

    @Test
    fun `LRC 时间戳展开到毫秒`() {
        val lines = parseLrcText("[01:02.34]第一行\n[00:00.00]第零行")
        assertEquals(listOf(0L, 62_340L), lines.map { it.timeMs })
        assertEquals("第零行", lines[0].text)
    }

    @Test
    fun `LRC 小数位归一为三位`() {
        // 小数位短于三位按末尾补零解释；长于三位截断到毫秒，避免 ".1234" 被读成 1234ms
        assertEquals(500L, parseLrcText("[00:00.5]半秒").single().timeMs)
        assertEquals(123L, parseLrcText("[00:00.1234]截断").single().timeMs)
    }

    @Test
    fun `LRC 空文本行被丢弃`() {
        val lines = parseLrcText("[00:01.00]有词\n[00:02.00]   \n无时间戳的行")
        assertEquals(listOf("有词"), lines.map { it.text })
    }

    // -----------------------------------------------------------------------
    // 译文合并
    // -----------------------------------------------------------------------

    @Test
    fun `翻译按时间戳精确配对`() {
        val lines = parseLrcText("[00:01.00]one\n[00:02.00]two")
        val trans = parseLrcText("[00:01.00]一\n[00:02.00]二")
        assertEquals(listOf("一", "二"), mergeTranslations(lines, trans).map { it.translation })
    }

    @Test
    fun `翻译在窗口内回落到最近一行`() {
        val lines = parseLrcText("[00:01.00]one\n[00:05.00]two")
        val trans = parseLrcText("[00:01.20]一")
        val merged = mergeTranslations(lines, trans)
        // 200ms 偏差在容差内可配对；4s 偏差已远超容差，宁可不挂译文也不挂错行
        assertEquals("一", merged[0].translation)
        assertNull(merged[1].translation)
    }

    @Test
    fun `空输入原样返回`() {
        val lines = parseLrcText("[00:01.00]one")
        assertEquals(lines, mergeTranslations(lines, emptyList()))
        assertTrue(mergeTranslations(emptyList(), lines).isEmpty())
    }

    // -----------------------------------------------------------------------
    // 增强 LRC（行内逐字标签）
    // -----------------------------------------------------------------------

    @Test
    fun `逐字 LRC 得到绝对字起点`() {
        val line = parseWordTimedLrcText("[00:01.00]<00:01.00>你<00:01.20>好").single()
        assertEquals(1_000L, line.timeMs)
        assertEquals(listOf(1_000L, 1_200L), line.words.map { it.startMs })
        assertEquals(listOf("你", "好"), line.words.map { it.text })
        assertEquals("你好", line.text)
    }

    @Test
    fun `逐字 LRC 支持小时形式`() {
        // 长音频（现场/播客）的行时间戳带小时段
        val line = parseWordTimedLrcText("[01:00:01.500]整点后").single()
        assertEquals(3_601_500L, line.timeMs)
    }

    @Test
    fun `逐字 LRC 提取翻译块`() {
        val line = parseWordTimedLrcText("[00:01.00]<00:01.00>你<00:01.20>好[tr]Hello[/tr]").single()
        assertEquals("Hello", line.translation)
        // 译文块必须先剥离再切字，否则 "[tr]Hello[/tr]" 会被当成末字文本
        assertEquals("你好", line.text)
    }

    @Test
    fun `无逐字标记的 LRC 退化为整行`() {
        val line = parseWordTimedLrcText("[00:01.00]整行一个时间点").single()
        assertEquals("整行一个时间点", line.text)
        assertTrue(line.words.isEmpty())
    }

    // -----------------------------------------------------------------------
    // QRC（QQ 音乐，绝对字时间）
    // -----------------------------------------------------------------------

    @Test
    fun `QRC 字起点本身即绝对`() {
        val line = parseQrcText("[0,3000]你(0,300)好(300,400)").single()
        assertEquals(0L, line.timeMs)
        assertEquals(listOf(0L, 300L), line.words.map { it.startMs })
        assertEquals(listOf(300L, 400L), line.words.map { it.durationMs })
        assertEquals("你好", line.text)
    }

    @Test
    fun `QRC 跳过元数据行`() {
        // "[ti:标题]" 不匹配数字行标签，元信息行被整行跳过
        assertTrue(parseQrcText("[ti:歌名]\n[ar:歌手]").isEmpty())
    }

    // -----------------------------------------------------------------------
    // KRC（酷狗，字偏移相对行首）
    // -----------------------------------------------------------------------

    @Test
    fun `KRC 字偏移以行首为基准`() {
        val line = parseKrcText("[1000,3000]<0,300,0>你<300,400,0>好").single()
        assertEquals(1_000L, line.timeMs)
        // 行起点 1000 + 字偏移 0/300：偏移必须叠加行起点才是可播放的绝对时间
        assertEquals(listOf(1_000L, 1_300L), line.words.map { it.startMs })
        assertEquals("你好", line.text)
    }

    @Test
    fun `KRC 零偏移行并入翻译`() {
        // 翻译行与主行同时间戳、字标签全零，是全行共享一个时间点的平台约定
        val lines = parseKrcText("[0,3000]<0,300,0>你<300,400,0>好\n[0,0]<0,0,0>Hello")
        assertEquals(1, lines.size)
        assertEquals("Hello", lines.single().translation)
    }

    @Test
    fun `KRC 孤立翻译保留为整行`() {
        // 主行为空时译文没有可挂靠的行，丢弃会整句丢失，退化为普通行至少不丢内容
        val lines = parseKrcText("[0,0]<0,0,0>只有译文")
        assertEquals(1, lines.size)
        assertEquals("只有译文", lines.single().text)
    }

    @Test
    fun `KRC 语言元数据并入翻译`() {
        // 酷狗的译文写在 [language:<base64>] 元信息行里，按歌词行顺序 1:1 对齐；
        // type=0 是音译，只取 type=1 的译文
        val language = """{"content":[{"type":0,"lyricContent":[["ni "],["hao "]]},{"type":1,"lyricContent":[["你好"]]}],"version":1}"""
        val encoded = Base64.getEncoder().encodeToString(language.toByteArray(Charsets.UTF_8))
        val lines = parseKrcText("[ti:歌名]\n[language:$encoded]\n[0,3000]<0,300,0>你<300,400,0>好")
        assertEquals(1, lines.size)
        assertEquals("你好", lines.single().translation)
    }

    @Test
    fun `KRC 翻译跟随歌词行序`() {
        // 无译文的行在数组中占位为空串，译文须按行序错位对齐，而非按下标直接配对
        val language = """{"content":[{"type":1,"lyricContent":[[""],["第一句译文"],[""]]}],"version":1}"""
        val encoded = Base64.getEncoder().encodeToString(language.toByteArray(Charsets.UTF_8))
        val lines = parseKrcText("[language:$encoded]\n[0,1000]one\n[1000,1000]two\n[2000,1000]three")
        assertNull(lines[0].translation)
        assertEquals("第一句译文", lines[1].translation)
        assertNull(lines[2].translation)
    }

    // -----------------------------------------------------------------------
    // 酷我 lrcx（区间两端编码值）
    // -----------------------------------------------------------------------

    @Test
    fun `酷我编码边界解码为字区间`() {
        // (和, 差) 变形且放大 8 倍：字起点 = |尾+起|/16，字终点 = max(|尾|,|起|)/8
        // 1600/-1600 → 起点 0、终点 200；3200/0 → 起点 200、终点 400
        val line = parseKuwoLrcxText("[00:00.000]<1600,-1600>你<3200,0>好").single()
        assertEquals(0L, line.timeMs)
        assertEquals(listOf(0L, 200L), line.words.map { it.startMs })
        assertEquals(listOf(200L, 200L), line.words.map { it.durationMs })
    }

    @Test
    fun `酷我重叠区间在下一字处截断`() {
        // 原始数据的相邻区间允许重叠，逐字高亮必须单调推进，故按下一字起点截断
        val line = parseKuwoLrcxText("[00:00.000]<1600,-1600>你<2400,800>好").single()
        // 第二字：起点 |2400+800|/16 = 200，终点 max(2400,800)/8 = 300
        assertEquals(listOf(0L, 200L), line.words.map { it.startMs })
        assertEquals(listOf(200L, 100L), line.words.map { it.durationMs })
    }

    @Test
    fun `酷我翻译按行时间戳配对`() {
        val lines = parseKuwoLrcxText("[00:01.000]<1600,-1600>你<3200,0>好\n[00:01.000]<0,0>Hello")
        assertEquals(1, lines.size)
        assertEquals("Hello", lines.single().translation)
    }

    // -----------------------------------------------------------------------
    // zlib 解压
    // -----------------------------------------------------------------------

    @Test
    fun `inflate 对 deflate 数据往返一致`() {
        val source = "逐字歌词内容".toByteArray(Charsets.UTF_8)
        assertEquals(String(source), String(inflateBytes(deflate(source))!!, Charsets.UTF_8))
    }

    @Test
    fun `inflate 拒绝非法数据`() {
        assertNull(inflateBytes(byteArrayOf(1, 2, 3, 4)))
        assertNull(inflateBytes(ByteArray(0)))
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        return out.toByteArray()
    }
}
