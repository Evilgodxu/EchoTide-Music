package com.yichao.evilgodxu.data.music.analysis

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WAV 生成器署名取证的定位复核：RIFF 的元数据块（承载 ID3v2 的 "ID3 " 块、LIST/INFO）
 * 常落在 data 之后，位置由块表决定而非文件末尾，超出头尾扫描窗口时须按绝对偏移定点读取。
 * 合成样本按 RIFF 与 ID3v2 规范排布，不依赖被测代码的写入结果
 */
class AiSourceTagProbeTest {

    @Test
    fun wavId3ChunkBeyondScanWindowsIsFound() {
        val tag = id3Tag("TENC" to "Suno")
        val buffer = wavFile(
            chunk("fmt ", fmtPayload()),
            chunk("data", ByteArray(DATA_BYTES)),
            chunk("ID3 ", tag),
            filler(FILLER_BYTES),
        )
        // 标签块既不在 128KB 头窗内，也不在 128KB 尾窗内：只有按块表定点读取才取得到
        val tagOffset = chunkOffset(buffer, "ID3 ")
        assertTrue(tagOffset > HEAD_SCAN_BYTES)
        assertTrue(tagOffset + 8 + tag.size < buffer.size - TAIL_SCAN_BYTES)
        assertEquals(AiSourceTagProbe.TagEvidence("suno", "TENC"), probe(buffer))
    }

    @Test
    fun wavInfoChunkBeyondScanWindowsIsFound() {
        val buffer = wavFile(
            chunk("fmt ", fmtPayload()),
            chunk("data", ByteArray(DATA_BYTES)),
            chunk("LIST", infoPayload("ISFT" to "Suno")),
            filler(FILLER_BYTES),
        )
        assertTrue(chunkOffset(buffer, "LIST") > HEAD_SCAN_BYTES)
        assertTrue(chunkOffset(buffer, "LIST") < buffer.size - TAIL_SCAN_BYTES)
        assertEquals(AiSourceTagProbe.TagEvidence("suno", "ISFT"), probe(buffer))
    }

    @Test
    fun wavContentCredentialInFetchedChunkIsMatched() {
        val buffer = wavFile(
            chunk("fmt ", fmtPayload()),
            chunk("data", ByteArray(DATA_BYTES)),
            chunk("ID3 ", id3Tag("TXXX" to "C2PA manifest")),
            filler(FILLER_BYTES),
        )
        assertEquals(AiSourceTagProbe.TagEvidence("c2pa", "C2PA"), probe(buffer))
    }

    @Test
    fun wavInfoInsideHeaderWindowIsStillScanned() {
        val buffer = wavFile(
            chunk("fmt ", fmtPayload()),
            chunk("LIST", infoPayload("ISFT" to "Suno")),
            chunk("data", ByteArray(64)),
        )
        // 窗口已覆盖标签块时同样命中，且不依赖定点读取
        assertEquals(AiSourceTagProbe.TagEvidence("suno", "ISFT"), probe(buffer))
    }

    @Test
    fun wavWithoutGeneratorFieldsIsNotFlagged() {
        val buffer = wavFile(
            chunk("fmt ", fmtPayload()),
            chunk("data", ByteArray(64)),
            chunk("LIST", infoPayload("INAM" to "等一分钟", "IART" to "徐誉滕")),
        )
        assertNull(probe(buffer))
    }

    @Test
    fun wavBeyondWindowsWithoutReaderIsIgnored() {
        val buffer = wavFile(
            chunk("fmt ", fmtPayload()),
            chunk("data", ByteArray(DATA_BYTES)),
            chunk("ID3 ", id3Tag("TENC" to "Suno")),
            filler(FILLER_BYTES),
        )
        // 没有定点读取能力时退化为只认窗口：不误报，也不越界
        val head = buffer.copyOfRange(0, HEAD_SCAN_BYTES)
        assertNull(AiSourceTagProbe.probeBytes(head, null, buffer.size.toLong()))
    }

    @Test
    fun realWavTagBeyondWindowsIsWalkedWithoutFalsePositive() {
        val sample = File("../Temporary Test Resources/徐誉滕 - 等一分钟.wav")
        if (!sample.isFile) {
            println("样本缺失，跳过: ${sample.absolutePath}")
            return
        }
        // 真实样本：data 块 41MB、标签块紧随其后，两个扫描窗口都覆盖不到；
        // 定点读取走通即证明真实标签块能被解析且不误报（该曲为 2008 年商业发行，无生成器署名）
        val size = sample.length()
        val head = readSample(sample, 0L, HEAD_SCAN_BYTES)
        val tailOffset = (size - TAIL_SCAN_BYTES).coerceAtLeast(0)
        val tail = readSample(sample, tailOffset, (size - tailOffset).toInt())
        val evidence = AiSourceTagProbe.probeBytes(head, tail, size) { offset, count ->
            readSample(sample, offset, count)
        }
        File("build/wav-probe-ai-report.txt").writeText(
            "样本: ${sample.name} 大小=$size 头窗=${head.size} 尾窗=${tail.size}@$tailOffset 取证=${evidence ?: "无命中"}\n",
        )
        assertNull(evidence)
    }

    private fun readSample(file: File, offset: Long, count: Int): ByteArray = RandomAccessFile(file, "r").run {
        try {
            seek(offset)
            ByteArray(minOf(count.toLong(), length() - offset).toInt()).also { readFully(it) }
        } finally {
            close()
        }
    }

    // 复现判定入口的取窗方式：头窗 128KB，文件更大时再取尾窗 128KB
    private fun probe(buffer: ByteArray): AiSourceTagProbe.TagEvidence? {
        val head = buffer.copyOfRange(0, minOf(HEAD_SCAN_BYTES, buffer.size))
        val tail = if (buffer.size > head.size) {
            buffer.copyOfRange((buffer.size - TAIL_SCAN_BYTES).coerceAtLeast(0), buffer.size)
        } else {
            null
        }
        return AiSourceTagProbe.probeBytes(head, tail, buffer.size.toLong(), readerOf(buffer))
    }

    private fun readerOf(buffer: ByteArray): (Long, Int) -> ByteArray? = { offset, count ->
        if (offset < 0 || offset >= buffer.size) {
            null
        } else {
            buffer.copyOfRange(offset.toInt(), minOf(offset.toInt() + count, buffer.size))
        }
    }

    // ---- 合成文件 ----

    private fun wavFile(vararg chunks: ByteArray): ByteArray {
        val body = Builder().ascii("WAVE")
        chunks.forEach { body.bytes(it) }
        val bodyBytes = body.toBytes()
        return Builder().ascii("RIFF").u32le(bodyBytes.size).bytes(bodyBytes).toBytes()
    }

    private fun fmtPayload(): ByteArray =
        Builder().u16le(1).u16le(2).u32le(44_100).u32le(176_400).u16le(4).u16le(16).toBytes()

    // RIFF 块：标识 + 小端长度 + 载荷 + 偶数字节对齐
    private fun chunk(id: String, body: ByteArray): ByteArray {
        val out = Builder().ascii(id).u32le(body.size).bytes(body)
        if (body.size and 1 != 0) out.u8(0)
        return out.toBytes()
    }

    private fun filler(size: Int): ByteArray = chunk("JUNK", ByteArray(size))

    // LIST/INFO 载荷：项值以单字节 0 结尾
    private fun infoPayload(vararg items: Pair<String, String>): ByteArray {
        val out = Builder().ascii("INFO")
        items.forEach { (id, value) ->
            val bytes = value.toByteArray(StandardCharsets.UTF_8) + 0
            out.ascii(id).u32le(bytes.size).bytes(bytes)
            if (bytes.size and 1 != 0) out.u8(0)
        }
        return out.toBytes()
    }

    // ID3v2.4 标签：文本帧为「编码字节 + 正文」，长度字段为 28 位同步安全整数；
    // TXXX 帧的正文为「描述 + \0 + 值」，便于直接承载标记文本
    private fun id3Tag(vararg frames: Pair<String, String>): ByteArray {
        val body = ByteArrayOutputStream()
        frames.forEach { (id, value) ->
            val text = if (id.startsWith("TXXX")) {
                byteArrayOf(0) + "probe".toByteArray(StandardCharsets.ISO_8859_1) + 0 +
                    value.toByteArray(StandardCharsets.ISO_8859_1)
            } else {
                byteArrayOf(0) + value.toByteArray(StandardCharsets.ISO_8859_1)
            }
            body.write(id.toByteArray(StandardCharsets.ISO_8859_1))
            body.write(syncSafe(text.size))
            body.write(byteArrayOf(0, 0))
            body.write(text)
        }
        val payload = body.toByteArray()
        return Builder()
            .ascii("ID3").u8(4).u8(0).u8(0).bytes(syncSafe(payload.size)).bytes(payload)
            .toBytes()
    }

    private fun syncSafe(value: Int): ByteArray =
        byteArrayOf(
            (value shr 21 and 0x7F).toByte(),
            (value shr 14 and 0x7F).toByte(),
            (value shr 7 and 0x7F).toByte(),
            (value and 0x7F).toByte(),
        )

    private fun chunkOffset(bytes: ByteArray, id: String): Int {
        var p = 12
        while (p + 8 <= bytes.size) {
            val size = leInt(bytes, p + 4)
            if (size < 0 || p + 8 + size > bytes.size) break
            if (String(bytes, p, 4, Charsets.ISO_8859_1) == id) return p
            p += 8 + size + (size and 1)
        }
        return -1
    }

    private fun leInt(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or (bytes[at + 1].toInt() and 0xFF shl 8) or
            (bytes[at + 2].toInt() and 0xFF shl 16) or (bytes[at + 3].toInt() and 0xFF shl 24)

    private class Builder {
        private val out = ByteArrayOutputStream()

        fun ascii(text: String) = apply { out.write(text.toByteArray(Charsets.ISO_8859_1)) }
        fun u32le(value: Int) = apply { u8(value); u8(value ushr 8); u8(value ushr 16); u8(value ushr 24) }
        fun u16le(value: Int) = apply { u8(value); u8(value ushr 8) }
        fun u8(value: Int) = apply { out.write(value and 0xFF) }
        fun bytes(value: ByteArray) = apply { out.write(value) }
        fun toBytes(): ByteArray = out.toByteArray()
    }

    private companion object {
        const val HEAD_SCAN_BYTES = 128 * 1024
        const val TAIL_SCAN_BYTES = 128 * 1024
        // data 块远大于扫描窗口，标签块因而落在两个窗口之外
        const val DATA_BYTES = 2 * 1024 * 1024
        // 标签块之后的填充块，把标签块挤出尾窗
        const val FILLER_BYTES = 256 * 1024
    }
}
