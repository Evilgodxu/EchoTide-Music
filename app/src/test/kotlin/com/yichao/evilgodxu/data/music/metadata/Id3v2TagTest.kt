package com.yichao.evilgodxu.data.music.metadata

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ID3v2 标签构造与解析复核。
 *
 * 容器改写保留帧时必须以原版本重建：v2.3 是 32 位大端长度 + UTF-16 文本，
 * v2.4 是同步安全长度 + UTF-8，按错版本读会直接变成乱码。
 */
class Id3v2TagTest {

    // -----------------------------------------------------------------------
    // 同步安全整数
    // -----------------------------------------------------------------------

    @Test
    fun syncsafeRoundTripsWithin28Bits() {
        for (value in listOf(0, 1, 127, 128, 16_383, 1_000_000, 0x0FFFFFFF)) {
            assertEquals("$value", value, Id3v2Tag.syncsafe(Id3v2Tag.syncsafeBytes(value), 0))
        }
    }

    @Test
    fun syncsafeIgnoresTheHighBitOfEveryByte() {
        // 每字节仅低 7 位有效，最高位恒为 0 以免被误判为帧同步
        val bytes = Id3v2Tag.syncsafeBytes(0x0FFFFFFF)
        assertTrue(bytes.all { it.toInt() and 0x80 == 0 })
    }

    // -----------------------------------------------------------------------
    // 版本
    // -----------------------------------------------------------------------

    @Test
    fun versionIsReadFromTheTagItself() {
        val frames = frames { Id3v2Tag.textFrame(this, "TIT2", "标题", 4) }
        assertEquals(4, Id3v2Tag.versionOf(Id3v2Tag.buildTag(frames, 4)))
        assertEquals(3, Id3v2Tag.versionOf(Id3v2Tag.buildTag(frames, 3)))
    }

    @Test
    fun unsupportedVersionFallsBackToTheWrittenVersion() {
        // v2.2 及更早的帧头结构不同，按新建版本重建而非按无法解析的版本读
        val tag = ByteArray(16).also {
            "ID3".toByteArray(StandardCharsets.US_ASCII).copyInto(it)
            it[3] = 2
        }
        assertEquals(Id3v2Tag.TAG_VERSION, Id3v2Tag.versionOf(tag))
        assertEquals(Id3v2Tag.TAG_VERSION, Id3v2Tag.versionOf(null))
        assertEquals(Id3v2Tag.TAG_VERSION, Id3v2Tag.versionOf(ByteArray(4)))
    }

    // -----------------------------------------------------------------------
    // 标签组装
    // -----------------------------------------------------------------------

    @Test
    fun buildTagDeclaresTheFrameAreaSize() {
        val frames = frames { Id3v2Tag.textFrame(this, "TIT2", "标题", 4) }
        val tag = Id3v2Tag.buildTag(frames, 4)
        assertEquals("ID3", String(tag, 0, 3, StandardCharsets.US_ASCII))
        assertEquals(4, tag[3].toInt() and 0xFF)
        assertEquals(frames.size, Id3v2Tag.syncsafe(tag, 6))
        assertEquals(10 + frames.size, tag.size)
    }

    @Test
    fun footerAppendsAMirroredHeaderWithoutChangingDeclaredSize() {
        // WAV 尾部标签需要 footer；footer 与头部除标识外字段一致，且不计入声明长度
        val frames = frames { Id3v2Tag.textFrame(this, "TIT2", "标题", 4) }
        val tag = Id3v2Tag.buildTag(frames, 4, flags = 0, footer = true)
        assertEquals(10 + frames.size + 10, tag.size)
        assertEquals("3DI", String(tag, tag.size - 10, 3, StandardCharsets.US_ASCII))
        assertEquals(frames.size, Id3v2Tag.syncsafe(tag, 6))
        assertEquals(frames.size, Id3v2Tag.syncsafe(tag, tag.size - 4))
    }

    // -----------------------------------------------------------------------
    // 帧改写
    // -----------------------------------------------------------------------

    @Test
    fun replaceFramesOverwritesTargetFrameAndKeepsTheRest() {
        val existing = Id3v2Tag.buildTag(
            frames {
                Id3v2Tag.textFrame(this, "TIT2", "旧标题", 4)
                Id3v2Tag.frame(this, "COMM", "保留注释".toByteArray(StandardCharsets.UTF_8), 4)
            },
            4,
        )
        val replaced = Id3v2Tag.replaceFrames(existing, title = "新标题", artist = null, album = null, cover = null, lyrics = null, version = 4)
        val written = Id3v2Tag.buildTag(replaced!!, 4)

        val parsed = walkFrames(written)
        assertEquals(listOf("TIT2", "COMM"), parsed.map { it.first })
        assertEquals("新标题", textOf(parsed[0].second))
        assertEquals("保留注释", String(parsed[1].second, StandardCharsets.UTF_8))
    }

    @Test
    fun replaceFramesAppendsFramesThatAreMissing() {
        val existing = Id3v2Tag.buildTag(frames { Id3v2Tag.textFrame(this, "TIT2", "标题", 4) }, 4)
        val replaced = Id3v2Tag.replaceFrames(existing, null, null, null, null, lyrics = "新词", version = 4)
        val written = Id3v2Tag.buildTag(replaced!!, 4)
        assertEquals(listOf("TIT2", "USLT"), walkFrames(written).map { it.first })
        assertEquals("新词", Id3v2Tag.readUslt(written, 0))
    }

    @Test
    fun replaceFramesOnlyRewritesTheFirstOccurrence() {
        // 重复帧只在首次出现处被替换，其后同名帧原样保留（保留既有帧的语义优先于去重）
        val existing = Id3v2Tag.buildTag(
            frames {
                Id3v2Tag.textFrame(this, "TIT2", "旧标题", 4)
                Id3v2Tag.textFrame(this, "TIT2", "更旧的标题", 4)
            },
            4,
        )
        val replaced = Id3v2Tag.replaceFrames(existing, title = "新标题", artist = null, album = null, cover = null, lyrics = null, version = 4)
        val parsed = walkFrames(Id3v2Tag.buildTag(replaced!!, 4))
        assertEquals(listOf("TIT2", "TIT2"), parsed.map { it.first })
        assertEquals("新标题", textOf(parsed[0].second))
        assertEquals("更旧的标题", textOf(parsed[1].second))
    }

    @Test
    fun replaceFramesReturnsNullWhenThereIsNothingToWrite() {
        assertNull(
            Id3v2Tag.replaceFrames(null, null, null, null, null, null, version = 4)
        )
    }

    // -----------------------------------------------------------------------
    // 帧解析
    // -----------------------------------------------------------------------

    @Test
    fun usltIsWrittenAsUtf8InV4AndUtf16InV3() {
        val v4 = Id3v2Tag.buildTag(frames { Id3v2Tag.usltFrame(this, "第一行\n第二行", 4) }, 4)
        assertEquals("第一行\n第二行", Id3v2Tag.readUslt(v4, 0))
        // v2.3 没有 UTF-8 文本编码，歌词必须写成带 BOM 的 UTF-16，否则按原版本读回是乱码
        val v3 = Id3v2Tag.buildTag(frames { Id3v2Tag.usltFrame(this, "第一行", 3) }, 3)
        assertEquals("第一行", Id3v2Tag.readUslt(v3, 0))
    }

    @Test
    fun apicSkipsMimeTypeAndDescriptor() {
        val cover = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 1, 2, 3, 4)
        val tag = Id3v2Tag.buildTag(frames { Id3v2Tag.apicFrame(this, cover, 4) }, 4)
        assertEquals(cover.toList(), Id3v2Tag.readApic(tag, 0)!!.toList())
    }

    @Test
    fun apicIsAbsentWhenThereIsNoSuchFrame() {
        val tag = Id3v2Tag.buildTag(frames { Id3v2Tag.textFrame(this, "TIT2", "标题", 4) }, 4)
        assertNull(Id3v2Tag.readApic(tag, 0))
    }

    // -----------------------------------------------------------------------

    private fun frames(block: ByteArrayOutputStream.() -> Unit): ByteArray =
        ByteArrayOutputStream().apply(block).toByteArray()

    /**
     * 独立的帧遍历：按 ID3v2.4 规范逐帧读标识与长度，不复用被测代码的解析逻辑，
     * 以免两侧犯同一个错误时测试仍然通过。
     */
    private fun walkFrames(tag: ByteArray): List<Pair<String, ByteArray>> {
        val declared = declaredSize(tag)
        val end = (10 + declared).coerceAtMost(tag.size)
        val result = mutableListOf<Pair<String, ByteArray>>()
        var p = 10
        while (p + 10 <= end) {
            val id = String(tag, p, 4, StandardCharsets.ISO_8859_1)
            if (!id.all { it in 'A'..'Z' || it in '0'..'9' }) break
            val length = declaredSizeAt(tag, p + 4)
            if (p + 10 + length > end) break
            result += id to tag.copyOfRange(p + 10, p + 10 + length)
            p += 10 + length
        }
        return result
    }

    private fun declaredSize(tag: ByteArray): Int = declaredSizeAt(tag, 6)

    private fun declaredSizeAt(tag: ByteArray, at: Int): Int =
        (tag[at].toInt() and 0x7f shl 21) or (tag[at + 1].toInt() and 0x7f shl 14) or
            (tag[at + 2].toInt() and 0x7f shl 7) or (tag[at + 3].toInt() and 0x7f)

    /** 文本帧内容：首字节为编码号，其后为文本，末尾一个 0 结尾（v2.4 为 UTF-8） */
    private fun textOf(data: ByteArray): String {
        assertEquals(3, data[0].toInt() and 0xFF)
        return String(data, 1, data.size - 2, StandardCharsets.UTF_8)
    }
}
