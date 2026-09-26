package com.yichao.evilgodxu.data.music.metadata

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 无损容器标签读写的往返复核：构造各容器的合成文件，经写路径产出新文件后由读路径取回，
 * 并校验容器自身的结构字段（FORM 尺寸、DSF 元数据指针与文件长度、APE 页脚定位）同步正确。
 * 合成文件只按各容器的公开规范排布，不依赖被测代码的写入结果
 */
class LosslessContainerTagsTest {

    private val lyrics = "[00:01.00]第一行\n[00:03.50]第二行"
    private val cover = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte(), 1, 2, 3, 4)

    @Test
    fun aiffKeepsAudioAndWritesId3ChunkBeforeIt() {
        val source = aiff()
        val rewrite = LosslessContainerTags.write(source, "标题", "艺术家", "专辑", cover, lyrics)
        assertNotNull(rewrite)
        val rewritten = apply(source, rewrite!!)
        // FORM 尺寸自长度字段之后起算：总长减 8
        assertEquals(rewritten.size - 8, beInt(rewritten, 4))
        assertEquals("AIFF", rewritten.asAscii(8, 4))
        // 音频体原样保留
        assertArrayEquals(audioPayload(), payloadOf(rewritten, "SSND"))
        assertEquals(lyrics, LosslessContainerTags.readLyrics(rewritten, null, 0L, null))
        assertArrayEquals(cover, LosslessContainerTags.readCover(rewritten, null, 0L, null))
    }

    @Test
    fun aiffRewritesExistingTagInsteadOfAppendingSecondChunk() {
        val first = apply(aiff(), LosslessContainerTags.write(aiff(), "旧标题", null, null, null, lyrics)!!)
        val second = apply(first, LosslessContainerTags.write(first, "新标题", null, null, null, lyrics)!!)
        assertEquals(1, countChunks(second, "ID3 "))
        assertEquals(lyrics, LosslessContainerTags.readLyrics(second, null, 0L, null))
        // 覆盖写入：旧标题不留存
        assertTrue(!second.asUtf8().contains("旧标题"))
        assertTrue(second.asUtf8().contains("新标题"))
    }

    @Test
    fun dffKeepsAudioAndPatchesFormSize() {
        val source = dff()
        val rewrite = LosslessContainerTags.write(source, "标题", "艺术家", "专辑", cover, lyrics)
        assertNotNull(rewrite)
        val rewritten = apply(source, rewrite!!)
        // FRM8 尺寸字段为 64 位，自长度字段之后起算：总长减 12
        assertEquals((rewritten.size - 12).toLong(), beLong(rewritten, 4))
        assertArrayEquals(audioPayload(), payloadOf(rewritten, "DSD "))
        assertEquals(lyrics, LosslessContainerTags.readLyrics(rewritten, null, 0L, null))
        assertArrayEquals(cover, LosslessContainerTags.readCover(rewritten, null, 0L, null))
    }

    @Test
    fun dsfAppendsTagAndRepointsFileSizeAndMetadataPointer() {
        val source = dsf()
        val rewrite = LosslessContainerTags.write(source, "标题", "艺术家", "专辑", cover, lyrics)
        assertNotNull(rewrite)
        val rewritten = apply(source, rewrite!!)
        assertEquals(rewritten.size.toLong(), leLong(rewritten, 12))
        val pointer = leLong(rewritten, 20).toInt()
        assertTrue(pointer in 1 until rewritten.size)
        // 指针指向的标签紧跟音频体之后
        assertEquals("ID3", rewritten.asAscii(pointer, 3))
        assertEquals(lyrics, LosslessContainerTags.readLyrics(rewritten, null, 0L, null))
        assertArrayEquals(cover, LosslessContainerTags.readCover(rewritten, null, 0L, null))
        // 再次写入时按指针复用标签位置，不追加第二份
        val second = apply(rewritten, LosslessContainerTags.write(rewritten, "新标题", null, null, null, lyrics)!!)
        assertEquals("ID3", second.asAscii(leLong(second, 20).toInt(), 3))
        assertEquals(lyrics, LosslessContainerTags.readLyrics(second, null, 0L, null))
    }

    @Test
    fun apeWritesApev2TagAtEndAndKeepsId3v1() {
        val source = ape(withId3v1 = true)
        val rewrite = LosslessContainerTags.write(source, "标题", "艺术家", "专辑", cover, lyrics)
        assertNotNull(rewrite)
        val rewritten = apply(source, rewrite!!)
        // APEv2 标签位于音频之后、ID3v1 之前
        assertTrue(rewritten.asAscii(rewritten.size - APE_ID3V1_BYTES - APE_FOOTER_BYTES, 8) == "APETAGEX")
        assertTrue(rewritten.asAscii(rewritten.size - APE_ID3V1_BYTES, 3) == "TAG")
        assertEquals(lyrics, LosslessContainerTags.readLyrics(rewritten, rewritten, 0L, null))
        assertArrayEquals(cover, LosslessContainerTags.readCover(rewritten, rewritten, 0L, null))
    }

    @Test
    fun apeKeepsAudioPrefixByteForByte() {
        val source = ape(withId3v1 = false)
        val rewrite = LosslessContainerTags.write(source, null, "艺术家", null, null, null)!!
        val rewritten = apply(source, rewrite)
        assertArrayEquals(source, rewritten.copyOfRange(0, source.size))
    }

    @Test
    fun wavTrailingTagIsLocatedFromFooter() {
        val source = wavWithTrailingId3()
        val header = source.copyOfRange(0, 12)
        assertEquals(lyrics, LosslessContainerTags.readLyrics(header, source, 0L, null))
        assertArrayEquals(cover, LosslessContainerTags.readCover(header, source, 0L, null))
        // 尾窗只覆盖标签尾部时，按 footer 回推的绝对偏移定点读取
        val tailOffset = source.size / 2
        val half = source.copyOfRange(tailOffset, source.size)
        assertEquals(
            lyrics,
            LosslessContainerTags.readLyrics(header, half, tailOffset.toLong()) { offset, count ->
                val from = offset.toInt().coerceAtLeast(0)
                source.copyOfRange(from, (from + count).coerceAtMost(source.size))
            },
        )
    }

    @Test
    fun id3TagPreservesForeignFramesAndReplacesTargets() {
        val existing = ByteArrayOutputStream()
        val out = ByteArrayOutputStream()
        Id3v2Tag.textFrame(out, "TXXX", "自定义", Id3v2Tag.TAG_VERSION)
        Id3v2Tag.textFrame(out, "TIT2", "旧标题", Id3v2Tag.TAG_VERSION)
        existing.write(Id3v2Tag.buildTag(out.toByteArray(), Id3v2Tag.TAG_VERSION))
        val frames = Id3v2Tag.replaceFrames(existing.toByteArray(), "新标题", null, null, null, lyrics, Id3v2Tag.TAG_VERSION)
        assertNotNull(frames)
        val tag = Id3v2Tag.buildTag(frames!!, Id3v2Tag.TAG_VERSION)
        val text = tag.asUtf8()
        assertTrue(text.contains("自定义"))
        assertTrue(text.contains("新标题"))
        assertTrue(!text.contains("旧标题"))
        assertEquals(lyrics, Id3v2Tag.readUslt(tag, 0))
    }

    @Test
    fun id3v23TagIsReadBackWithUtf16Text() {
        val out = ByteArrayOutputStream()
        Id3v2Tag.usltFrame(out, lyrics, 3)
        val tag = Id3v2Tag.buildTag(out.toByteArray(), 3)
        assertEquals(3, tag[3].toInt())
        assertEquals(lyrics, Id3v2Tag.readUslt(tag, 0))
    }

    @Test
    fun aiffTagBeyondHeaderWindowIsReadByOffset() {
        val rewritten = apply(aiff(), LosslessContainerTags.write(aiff(), "标题", null, null, cover, lyrics)!!)
        // 头窗只截到标签块头：块表足以定位标签，块体（含封面）需按绝对偏移定点读取
        val header = rewritten.copyOfRange(0, 64)
        val readCovered = LosslessContainerTags.readCover(header, null, 0L) { offset, count ->
            val from = offset.toInt()
            rewritten.copyOfRange(from, (from + count).coerceAtMost(rewritten.size))
        }
        assertArrayEquals(cover, readCovered)
    }

    @Test
    fun unknownContainerIsRejected() {
        assertNull(LosslessContainerTags.write(ByteArray(64), "标题", null, null, null, null))
        assertNull(LosslessContainerTags.readLyrics(ByteArray(64), null, 0L, null))
    }

    // ---- 合成文件 ----

    private class Builder {
        private val out = ByteArrayOutputStream()

        fun ascii(text: String) = apply { out.write(text.toByteArray(Charsets.ISO_8859_1)) }
        fun u16le(value: Int) = apply { u8(value); u8(value ushr 8) }
        fun u32le(value: Int) = apply { u8(value); u8(value ushr 8); u8(value ushr 16); u8(value ushr 24) }
        fun u32be(value: Int) = apply { u8(value ushr 24); u8(value ushr 16); u8(value ushr 8); u8(value) }
        fun u64le(value: Long) = apply { u32le(value.toInt()); u32le((value ushr 32).toInt()) }
        fun u64le(value: Int) = u64le(value.toLong())
        fun u64be(value: Long) = apply { u32be((value ushr 32).toInt()); u32be(value.toInt()) }
        fun u64be(value: Int) = u64be(value.toLong())
        fun zeros(count: Int) = apply { repeat(count) { u8(0) } }
        fun bytes(value: ByteArray) = apply { out.write(value) }
        fun u8(value: Int) = apply { out.write(value and 0xFF) }
        fun toBytes(): ByteArray = out.toByteArray()
    }

    private fun audioPayload(): ByteArray = ByteArray(96) { (it + 1).toByte() }

    // AIFF：FORM + COMM + SSND
    private fun aiff(): ByteArray {
        val comm = Builder().u16le(2).u32le(96).u16le(16).bytes(ByteArray(10)).toBytes()
        return Builder()
            .ascii("FORM").u32be(0).ascii("AIFF")
            .ascii("COMM").u32be(comm.size).bytes(comm)
            .ascii("SSND").u32be(8 + audioPayload().size).u32le(0).u32le(0).bytes(audioPayload())
            .toBytes()
            .also { writeFormSize(it, 4, 4) }
    }

    // DSDIFF：FRM8 + FVER + PROP(FS/CHNL) + DSD
    private fun dff(): ByteArray {
        val prop = Builder().ascii("SND ")
            .ascii("FS  ").u64be(4).u32be(2_822_400)
            .ascii("CHNL").u64be(4).u16le(2).zeros(2)
            .toBytes()
        return Builder()
            .ascii("FRM8").u64be(0).ascii("DSD ")
            .ascii("FVER").u64be(4).u32be(0x01050000)
            .ascii("PROP").u64be(prop.size).bytes(prop)
            .ascii("DSD ").u64be(audioPayload().size.toLong()).bytes(audioPayload())
            .toBytes()
            .also { writeFormSize(it, 4, 8) }
    }

    // DSF：DSD 头(28) + fmt + data
    private fun dsf(): ByteArray {
        val fmtBody = Builder().u32le(1).u32le(0).u32le(2).u32le(2)
            .u32le(2_822_400).u32le(1).u64le(0).u32le(4096).u32le(0).toBytes()
        val fmt = Builder().u64le(fmtBody.size.toLong()).bytes(fmtBody).toBytes()
        val data = Builder().u64le(audioPayload().size.toLong()).bytes(audioPayload()).toBytes()
        val total = 28 + 4 + fmt.size + 4 + data.size
        return Builder()
            .ascii("DSD ").u64le(28).u64le(total.toLong()).u64le(0)
            .ascii("fmt ").bytes(fmt)
            .ascii("data").bytes(data)
            .toBytes()
    }

    // APE：MAC 描述符 + APE_HEADER + 音频体（+ 可选 ID3v1）
    private fun ape(withId3v1: Boolean): ByteArray {
        val out = Builder()
            .ascii("MAC ").u16le(3980).u16le(0)
            .u32le(52).u32le(24).u32le(0).u32le(0).u32le(0).u32le(0).u32le(0)
            .zeros(16)
            .u16le(2000).u16le(0).u32le(73_728).u32le(0).u32le(0)
            .u16le(16).u16le(2).u32le(44_100)
            .bytes(ByteArray(128) { 0x2A })
        if (withId3v1) out.ascii("TAG").zeros(APE_ID3V1_BYTES - 3)
        return out.toBytes()
    }

    // WAV：RIFF 头 + 尾部带 footer 的 ID3v2 标签（与写出器产出的布局一致）
    private fun wavWithTrailingId3(): ByteArray {
        val frames = Id3v2Tag.replaceFrames(null, "标题", "艺术家", null, cover, lyrics, Id3v2Tag.TAG_VERSION)!!
        val tag = Id3v2Tag.buildTag(frames, Id3v2Tag.TAG_VERSION, 0x10, footer = true)
        val data = ByteArray(64) { 0x33 }
        return Builder()
            .ascii("RIFF").u32le(4 + 8 + data.size).ascii("WAVE")
            .ascii("data").u32le(data.size).bytes(data)
            .bytes(tag)
            .toBytes()
    }

    // 按写路径返回的三段结构拼出新文件
    private fun apply(source: ByteArray, rewrite: LosslessContainerTags.TagRewrite): ByteArray = Builder()
        .bytes(rewrite.head)
        .bytes(source.copyOfRange(rewrite.bodyStart, rewrite.bodyEnd))
        .bytes(rewrite.tail)
        .toBytes()

    // FORM/FRM8 尺寸字段按「总长减去标识与长度字段」回填，尺寸字段自身在偏移 4
    private fun writeFormSize(bytes: ByteArray, sizeFieldOffset: Int, sizeBytes: Int) {
        val value = (bytes.size - (4 + sizeBytes)).toLong()
        for (i in 0 until sizeBytes) {
            bytes[sizeFieldOffset + sizeBytes - 1 - i] = (value ushr (8 * i)).toByte()
        }
    }

    private fun isDff(bytes: ByteArray) = bytes.asAscii(0, 4) == "FRM8"

    // 取指定 IFF 块的载荷，用于核对音频体未被改写
    private fun payloadOf(bytes: ByteArray, chunkId: String): ByteArray {
        var p = if (isDff(bytes)) 16 else 12
        while (p + 12 <= bytes.size) {
            val id = bytes.asAscii(p, 4)
            val header = if (isDff(bytes)) 12 else 8
            val size = if (isDff(bytes)) beLong(bytes, p + 4).toInt() else beInt(bytes, p + 4)
            if (size <= 0) break
            if (id == chunkId) {
                // SSND 载荷前置 8 字节的偏移与块长
                val from = p + header + if (chunkId == "SSND") 8 else 0
                return bytes.copyOfRange(from, from + audioPayload().size)
            }
            p += header + size + (size and 1)
        }
        return ByteArray(0)
    }

    private fun countChunks(bytes: ByteArray, chunkId: String): Int {
        var p = if (isDff(bytes)) 16 else 12
        var count = 0
        while (p + 12 <= bytes.size) {
            val id = bytes.asAscii(p, 4)
            val header = if (isDff(bytes)) 12 else 8
            val size = if (isDff(bytes)) beLong(bytes, p + 4).toInt() else beInt(bytes, p + 4)
            if (size <= 0 || p + header + size > bytes.size) break
            if (id == chunkId) count++
            p += header + size + (size and 1)
        }
        return count
    }

    private fun beInt(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF shl 24) or (bytes[at + 1].toInt() and 0xFF shl 16) or
            (bytes[at + 2].toInt() and 0xFF shl 8) or (bytes[at + 3].toInt() and 0xFF)

    private fun beLong(bytes: ByteArray, at: Int): Long =
        (beInt(bytes, at).toLong() shl 32) or (beInt(bytes, at + 4).toLong() and 0xFFFFFFFFL)

    private fun leLong(bytes: ByteArray, at: Int): Long {
        var value = 0L
        for (i in 7 downTo 0) value = (value shl 8) or (bytes[at + i].toLong() and 0xFF)
        return value
    }

    private fun ByteArray.asAscii(at: Int, length: Int): String =
        if (at < 0 || at + length > size) "" else String(this, at, length, Charsets.ISO_8859_1)

    private fun ByteArray.asUtf8(): String = String(this, Charsets.UTF_8)

    private companion object {
        const val APE_ID3V1_BYTES = 128
        const val APE_FOOTER_BYTES = 32
    }
}
