package com.yichao.evilgodxu.data.music.metadata

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 实测探针：用真实 WAV 样本核对容器内 "ID3 " 块的读取与写回。
 * 样本不在仓库内（Temporary Test Resources），缺失时跳过；结论写入 build/wav-probe-report.txt
 */
class WavRealFileProbeTest {

    private val report = StringBuilder()
    private val sample = File("../Temporary Test Resources/徐誉滕 - 等一分钟.wav")

    @Test
    fun realWavCoverAndLyricsRoundTrip() {
        if (!sample.isFile) {
            println("样本缺失，跳过: ${sample.absolutePath}")
            return
        }
        val size = sample.length()
        val header = sample.inputStream().use { LocalAudioSource.readUpTo(it, HEADER_CAP) }
        val tailOffset = (size - TAIL_CAP).coerceAtLeast(0)
        val tail = RandomAccessFile(sample, "r").run {
            try {
                seek(tailOffset)
                ByteArray((size - tailOffset).toInt()).also { readFully(it) }
            } finally {
                close()
            }
        }
        report.appendLine("样本: ${sample.name} 大小=$size 头窗=${header.size} 尾窗=${tail.size}@$tailOffset")

        // ---- 读：对齐 App 的两段窗口 + 窗口外定点读取 ----
        val headerCover = LosslessContainerTags.readCover(header, null, 0L, null)
        val lyrics = LosslessContainerTags.readLyrics(header, tail, tailOffset, ::readRange)
        val cover = LosslessContainerTags.readCover(header, tail, tailOffset, ::readRange)
        report.appendLine("仅头窗读封面: ${describe(headerCover)}")
        report.appendLine("头窗+尾窗读歌词: ${lyrics?.lineSequence()?.count() ?: 0} 行, 首行=${lyrics?.lineSequence()?.firstOrNull()}")
        report.appendLine("头窗+尾窗读封面: ${describe(cover)}")
        assertNotNull("容器内 ID3 块的歌词未读回", lyrics)
        assertTrue("歌词内容异常", lyrics!!.contains("[") || lyrics.isNotBlank())
        assertNotNull("容器内 ID3 块的封面未读回", cover)
        assertArrayEquals("封面字节与文件内 APIC 不一致", apicOf(sample), cover)

        // ---- 写：整文件重写，音频体按区间流式复制 ----
        val source = sample.readBytes()
        val target = File("build/wav-probe-rewritten.wav")
        try {            val rewrite = LosslessContainerTags.write(source, "探针标题", "探针艺术家", "探针专辑", cover, lyrics)
            assertNotNull("真实样本写出失败", rewrite)
            val plan = rewrite!!
            target.outputStream().use { out ->
                out.write(plan.head)
                out.write(source, plan.bodyStart, plan.bodyEnd - plan.bodyStart)
                out.write(plan.tail)
            }
            val rewritten = target.readBytes()
            report.appendLine("写回: 源=$size 新=${rewritten.size} 头部=${plan.head.size} 音频体=${plan.bodyEnd - plan.bodyStart} 尾部=${plan.tail.size}")

            val chunks = wavChunks(rewritten)
            report.appendLine("新文件块表: " + chunks.joinToString(" ") { "${it.first}(${it.third})@${it.second}" })
            assertEquals("RIFF 尺寸未同步", rewritten.size - 8, leInt(rewritten, 4))
            assertEquals("容器内标签块不止一份", 1, chunks.count { it.first == "ID3 " })
            assertEquals("INFO 块不止一份", 1, chunks.count { it.first == "LIST" })
            assertTrue("容器外仍有末尾标签", rewritten.asAscii(rewritten.size - 10, 3) != "3DI")

            // 音频载荷逐字节不变
            val dataBefore = payloadOf(source, wavChunks(source), "data")
            val dataAfter = payloadOf(rewritten, chunks, "data")
            assertEquals("data 载荷长度变化", dataBefore.size, dataAfter.size)
            assertArrayEquals("data 载荷被改写", sha256(dataBefore), sha256(dataAfter))

            // 读回：新值可读、旧值不留存、既有帧与 INFO 项保留
            val readBackLyrics = LosslessContainerTags.readLyrics(rewritten, rewritten, 0L, null)
            val readBackCover = LosslessContainerTags.readCover(rewritten, rewritten, 0L, null)
            assertEquals("写回后歌词不一致", lyrics, readBackLyrics)
            assertArrayEquals("写回后封面不一致", cover, readBackCover)
            val text = String(rewritten, StandardCharsets.UTF_8)
            val infoText = String(payloadOf(rewritten, chunks, "LIST"), StandardCharsets.UTF_8)
            report.appendLine("写回校验: TIT2=${text.contains("探针标题")} TPE1=${text.contains("探针艺术家")} " +
                "TALB=${text.contains("探针专辑")} 保留帧=${text.contains("TPOS") && text.contains("TDRC") && text.contains("TXXX")} " +
                "INFO=${infoText.replace("\u0000", "|")}")
            assertTrue("新标题未写入", text.contains("探针标题"))
            assertTrue("既有帧（TPOS/TDRC/TXXX）未保留", text.contains("TPOS") && text.contains("TDRC") && text.contains("TXXX"))
            assertTrue("INFO 标题未更新", infoText.contains("探针标题"))
            assertTrue("INFO 既有项未保留", infoText.contains("ICRD") && infoText.contains("2008-10-22"))
        } finally {
            if (target.exists()) target.delete()
        }
        File("build/wav-probe-report.txt").writeText(report.toString())
        println(report.toString())
    }

    private fun readRange(offset: Long, count: Int): ByteArray? = RandomAccessFile(sample, "r").run {
        try {
            if (offset < 0 || offset >= length()) return null
            seek(offset)
            ByteArray(minOf(count.toLong(), length() - offset).toInt()).also { readFully(it) }
        } finally {
            close()
        }
    }

    // 直接按 ID3 标签结构取 APIC 载荷，作为自实现读取结果的独立对照
    private fun apicOf(file: File): ByteArray {
        val bytes = file.readBytes()
        val chunk = wavChunks(bytes).first { it.first == "ID3 " }
        val tag = bytes.copyOfRange(chunk.second + 8, chunk.second + 8 + chunk.third)
        val end = 10 + (tag[6].toInt() and 0x7f shl 21 or (tag[7].toInt() and 0x7f shl 14) or (tag[8].toInt() and 0x7f shl 7) or (tag[9].toInt() and 0x7f))
        var p = 10
        while (p + 10 <= end) {
            val id = String(tag, p, 4, StandardCharsets.ISO_8859_1)
            val length = (tag[p + 4].toInt() and 0x7f shl 21) or (tag[p + 5].toInt() and 0x7f shl 14) or
                (tag[p + 6].toInt() and 0x7f shl 7) or (tag[p + 7].toInt() and 0x7f)
            if (id == "APIC") {
                var q = p + 10
                q++ // 编码字节
                while (q < p + 10 + length && tag[q] != 0.toByte()) q++
                q += 2 // MIME 结尾 + 图片类型
                while (q < p + 10 + length && tag[q] != 0.toByte()) q++
                q++
                return tag.copyOfRange(q, p + 10 + length)
            }
            p += 10 + length
        }
        return ByteArray(0)
    }

    private fun payloadOf(bytes: ByteArray, chunks: List<Triple<String, Int, Int>>, id: String): ByteArray {
        val chunk = chunks.first { it.first == id }
        return bytes.copyOfRange(chunk.second + 8, chunk.second + 8 + chunk.third)
    }

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

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun describe(bytes: ByteArray?): String =
        if (bytes == null) "未读到" else "${bytes.size} 字节, 头部=${bytes.take(8).joinToString("") { "%02x".format(it) }}"

    private fun leInt(bytes: ByteArray, at: Int): Int {
        var value = 0
        for (i in 3 downTo 0) value = (value shl 8) or (bytes[at + i].toInt() and 0xFF)
        return value
    }

    private fun ByteArray.asAscii(at: Int, length: Int): String =
        if (at < 0 || at + length > size) "" else String(this, at, length, Charsets.ISO_8859_1)

    private companion object {
        const val HEADER_CAP = 512 * 1024
        const val TAIL_CAP = 2 * 1024 * 1024
    }
}
