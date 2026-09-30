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
    fun wavEmbeddedId3ChunkAfterAudioIsReadFromWindows() {
        val source = wavWithEmbeddedId3()
        // 整个文件即窗口时，容器内标签块在窗口内即可定位
        assertEquals(lyrics, LosslessContainerTags.readLyrics(source, null, 0L, null))
        assertArrayEquals(cover, LosslessContainerTags.readCover(source, null, 0L, null))
        // 头窗只覆盖容器头与 fmt：data 之后的标签块由尾窗给出块头，块体再按绝对偏移定点读取
        val header = source.copyOfRange(0, 64)
        val tailOffset = source.size / 2
        val tail = source.copyOfRange(tailOffset, source.size)
        assertEquals(lyrics, LosslessContainerTags.readLyrics(header, tail, tailOffset.toLong(), readerOf(source)))
        assertArrayEquals(cover, LosslessContainerTags.readCover(header, tail, tailOffset.toLong(), readerOf(source)))
    }

    @Test
    fun wavRewriteCollapsesTagAndInfoChunksIntoOneEach() {
        val source = wavWithEmbeddedId3()
        val rewrite = LosslessContainerTags.write(source, "新标题", "新艺术家", "新专辑", cover, lyrics)
        assertNotNull(rewrite)
        val rewritten = apply(source, rewrite!!)
        // RIFF 尺寸自长度字段之后起算：总长减 8；容器内只剩一份标签块与一份 INFO 块
        assertEquals(rewritten.size - 8, leInt(rewritten, 4))
        assertEquals(1, countWavChunks(rewritten, "ID3 "))
        assertEquals(1, countWavChunks(rewritten, "LIST"))
        // 音频载荷逐字节保留，标签块紧随 data，INFO 块置于 data 之前
        assertArrayEquals(audioPayload(), wavPayloadOf(rewritten, "data"))
        assertTrue(wavChunkOffset(rewritten, "ID3 ")!! > wavChunkOffset(rewritten, "data")!!)
        assertTrue(wavChunkOffset(rewritten, "LIST")!! < wavChunkOffset(rewritten, "data")!!)
        // 读写往返：新值可读回，旧值不留存
        assertEquals(lyrics, LosslessContainerTags.readLyrics(rewritten, null, 0L, null))
        assertArrayEquals(cover, LosslessContainerTags.readCover(rewritten, null, 0L, null))
        val text = rewritten.asUtf8()
        assertTrue(text.contains("新标题") && text.contains("新艺术家") && text.contains("新专辑"))
        assertTrue(!text.contains("旧标题") && !text.contains("旧艺术家"))
        assertEquals(1, countInfoItems(rewritten, "INAM"))
        assertEquals(1, countInfoItems(rewritten, "IART"))
        // 既有 INFO 项原样保留，新建项按 RIFF 惯例以单字节 0 结尾
        assertEquals(1, countInfoItems(rewritten, "ICRD"))
        assertEquals(0, infoValues(rewritten, "INAM").single().last().toInt())
    }

    @Test
    fun wavTextTagsAreReadBackFromId3Chunk() {
        val source = wavWithEmbeddedId3()
        val rewrite = LosslessContainerTags.write(source, "新标题", "新艺术家", "新专辑", null, null)
        assertNotNull(rewrite)
        val rewritten = apply(source, rewrite!!)
        val tag = LosslessContainerTags.readText(rewritten, null, 0L, null)
        assertNotNull(tag)
        assertEquals("新标题", tag!!.title)
        assertEquals("新艺术家", tag.artist)
        assertEquals("新专辑", tag.album)
    }

    // 只有 LIST/INFO 的文件（第三方工具写出的布局）：没有 ID3 块时文本标签仍能从 INFO 项取回
    @Test
    fun wavTextTagsAreReadBackFromInfoChunkOnly() {
        val source = wavFile(
            wavChunk("fmt ", ByteArray(16)),
            wavChunk("data", audioPayload()),
            wavChunk("LIST", wavInfo("INAM" to "标题", "IART" to "艺术家", "IPRD" to "专辑")),
        )
        val tag = LosslessContainerTags.readText(source, null, 0L, null)
        assertNotNull(tag)
        assertEquals("标题", tag!!.title)
        assertEquals("艺术家", tag.artist)
        assertEquals("专辑", tag.album)
    }

    // 头窗只覆盖容器头与 fmt 时，data 之后的标签块与 INFO 块由尾窗给出块头，块体按绝对偏移定点读取
    @Test
    fun wavTextTagsAreReadFromWindowsAndOutOfWindowChunks() {
        val source = wavWithEmbeddedId3()
        val header = source.copyOfRange(0, 64)
        val tailOffset = source.size / 2
        val tail = source.copyOfRange(tailOffset, source.size)
        val tag = LosslessContainerTags.readText(header, tail, tailOffset.toLong(), readerOf(source))
        assertNotNull(tag)
        assertEquals("旧标题", tag!!.title)
        assertEquals("旧艺术家", tag.artist)
    }

    @Test
    fun wavRewriteMergesLegacyTrailingTagIntoContainerChunk() {
        val source = wavWithLegacyTrailingTag()
        val rewrite = LosslessContainerTags.write(source, "新标题", null, null, null, lyrics)
        assertNotNull(rewrite)
        val rewritten = apply(source, rewrite!!)
        // 容器外不再挂标签，容器内只有一份
        assertEquals(rewritten.size - 8, leInt(rewritten, 4))
        assertEquals(1, countWavChunks(rewritten, "ID3 "))
        assertTrue(rewritten.asAscii(rewritten.size - 10, 3) != "3DI")
        // 旧标签的保留帧（TXXX）随重写保留，被覆盖的帧不留旧值
        val text = rewritten.asUtf8()
        assertTrue(text.contains("保留帧") && text.contains("新标题"))
        assertTrue(!text.contains("旧标题"))
        assertEquals(lyrics, LosslessContainerTags.readLyrics(rewritten, rewritten, 0L, null))
    }

    @Test
    fun wavOddDataChunkKeepsPadByteBeforeTagChunk() {
        val source = wavWithEmbeddedId3(oddAudio = true)
        val rewrite = LosslessContainerTags.write(source, "新标题", null, null, null, lyrics)
        assertNotNull(rewrite)
        val rewritten = apply(source, rewrite!!)
        assertEquals(rewritten.size - 8, leInt(rewritten, 4))
        // 奇数长度 data 块的对齐字节仍位于载荷之后，标签块随之顺延
        val dataOffset = wavChunkOffset(rewritten, "data")!!
        val size = leInt(rewritten, dataOffset + 4)
        assertEquals(1, size and 1)
        assertArrayEquals(oddAudioPayload(), rewritten.copyOfRange(dataOffset + 8, dataOffset + 8 + size))
        assertEquals("ID3 ", rewritten.asAscii(dataOffset + 8 + size + 1, 4))
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

    private fun oddAudioPayload(): ByteArray = ByteArray(97) { (it + 1).toByte() }

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

    // WAV：RIFF 头 + 尾部带 footer 的 ID3v2 标签（旧版本写出器的布局）
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

    // WAV：RIFF + fmt + data + 容器内 ID3 块 + LIST/INFO，与 ffmpeg 等工具写出的普遍布局一致
    private fun wavWithEmbeddedId3(oddAudio: Boolean = false): ByteArray {
        val frames = Id3v2Tag.replaceFrames(null, "旧标题", "旧艺术家", null, cover, lyrics, Id3v2Tag.TAG_VERSION)!!
        val fmt = Builder().u16le(1).u16le(2).u32le(44_100).u32le(176_400).u16le(4).u16le(16).toBytes()
        val audio = if (oddAudio) oddAudioPayload() else audioPayload()
        return wavFile(
            wavChunk("fmt ", fmt),
            wavChunk("data", audio),
            wavChunk("ID3 ", Id3v2Tag.buildTag(frames, Id3v2Tag.TAG_VERSION)),
            wavChunk("LIST", wavInfo("INAM" to "旧标题", "ICRD" to "2008-10-22")),
        )
    }

    // WAV：容器外文件末尾挂带 footer 的 ID3v2 标签（旧版本布局），标签内另有需保留的 TXXX 帧
    private fun wavWithLegacyTrailingTag(): ByteArray {
        val frames = ByteArrayOutputStream()
        Id3v2Tag.textFrame(frames, "TXXX", "保留帧", Id3v2Tag.TAG_VERSION)
        Id3v2Tag.textFrame(frames, "TIT2", "旧标题", Id3v2Tag.TAG_VERSION)
        val tag = Id3v2Tag.buildTag(frames.toByteArray(), Id3v2Tag.TAG_VERSION, 0x10, footer = true)
        val data = wavChunk("data", audioPayload())
        return Builder()
            .ascii("RIFF").u32le(4 + data.size).ascii("WAVE")
            .bytes(data)
            .bytes(tag)
            .toBytes()
    }

    // RIFF 块：标识 + 小端长度 + 载荷 + 偶数字节对齐
    private fun wavChunk(id: String, body: ByteArray): ByteArray {
        val out = Builder().ascii(id).u32le(body.size).bytes(body)
        if (body.size and 1 != 0) out.u8(0)
        return out.toBytes()
    }

    private fun wavFile(vararg chunks: ByteArray): ByteArray {
        val body = Builder().ascii("WAVE")
        chunks.forEach { body.bytes(it) }
        val bodyBytes = body.toBytes()
        return Builder().ascii("RIFF").u32le(bodyBytes.size).bytes(bodyBytes).toBytes()
    }

    // LIST/INFO 载荷：项值按惯例以单字节 0 结尾
    private fun wavInfo(vararg items: Pair<String, String>): ByteArray {
        val out = Builder().ascii("INFO")
        items.forEach { (id, value) ->
            val bytes = value.toByteArray(Charsets.UTF_8) + 0
            out.ascii(id).u32le(bytes.size).bytes(bytes)
            if (bytes.size and 1 != 0) out.u8(0)
        }
        return out.toBytes()
    }

    private fun readerOf(source: ByteArray): (Long, Int) -> ByteArray = { offset, count ->
        val from = offset.toInt().coerceIn(0, source.size)
        source.copyOfRange(from, (from + count).coerceAtMost(source.size))
    }

    // WAV 块表：块结构不成立即终止，返回（标识, 块头偏移, 载荷长度）
    private fun wavChunks(bytes: ByteArray): List<Triple<String, Int, Int>> {
        val chunks = mutableListOf<Triple<String, Int, Int>>()
        var p = 12
        while (p + 8 <= bytes.size) {
            val size = leInt(bytes, p + 4)
            if (size < 0 || p + 8 + size > bytes.size) break
            chunks += Triple(bytes.asAscii(p, 4), p, size)
            p += 8 + size + (size and 1)
        }
        return chunks
    }

    private fun countWavChunks(bytes: ByteArray, id: String): Int = wavChunks(bytes).count { it.first == id }

    private fun wavChunkOffset(bytes: ByteArray, id: String): Int? =
        wavChunks(bytes).firstOrNull { it.first == id }?.second

    private fun wavPayloadOf(bytes: ByteArray, id: String): ByteArray {
        val chunk = wavChunks(bytes).firstOrNull { it.first == id } ?: return ByteArray(0)
        return bytes.copyOfRange(chunk.second + 8, chunk.second + 8 + chunk.third)
    }

    private fun countInfoItems(bytes: ByteArray, id: String): Int = infoValues(bytes, id).size

    private fun infoValues(bytes: ByteArray, id: String): List<ByteArray> {
        val info = wavPayloadOf(bytes, "LIST")
        if (info.size < 4 || info.asAscii(0, 4) != "INFO") return emptyList()
        val values = mutableListOf<ByteArray>()
        var p = 4
        while (p + 8 <= info.size) {
            val size = leInt(info, p + 4)
            if (size < 0 || p + 8 + size > info.size) break
            if (info.asAscii(p, 4) == id) values += info.copyOfRange(p + 8, p + 8 + size)
            p += 8 + size + (size and 1)
        }
        return values
    }

    // 按写路径返回的三段结构拼出新文件
    private fun apply(source: ByteArray, rewrite: LosslessContainerTags.TagRewrite): ByteArray = Builder()
        .bytes(rewrite.head)
        .bytes(source.copyOfRange(rewrite.bodyStart.toInt(), rewrite.bodyEnd.toInt()))
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

    private fun leInt(bytes: ByteArray, at: Int): Int {
        var value = 0
        for (i in 3 downTo 0) value = (value shl 8) or (bytes[at + i].toInt() and 0xFF)
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
