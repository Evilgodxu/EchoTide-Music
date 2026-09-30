package com.yichao.evilgodxu.data.music.metadata

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文件源重写路径的实测：线上写盘（本地文件与 content URI）都经 `TagSource` 定位读取标签窗口、
 * 按区间搬运音频躯干，而字节入口把整段文件交给解析器，覆盖不到「定位读取 + 区间搬运」这一段。
 *
 * 用例分三层：
 *   1. 十个容器的样本落到真实文件后走文件源重写，逐字段读回并核对音频区未被改动；
 *   2. 二次写入（标签长度变化）后躯干偏移仍须正确；
 *   3. 超大文件：无损与线性 PCM 容器的块表只读块头，故不受尺寸限制；
 *      Ogg/Opus 仍需整条包流，超限按约定跳过。
 */
class MetadataRewriteFileSourceTest {

    @Test
    fun fileSourceRewriteMatchesByteEntryAcrossContainers() {
        FIXTURES.forEach { name ->
            val original = File(FIXTURE_DIR + name).readBytes()
            val work = File(tempDir(), name).apply { writeBytes(original) }
            try {
                val rewritten = MusicMetadataWriter.rewriteFileBytes(work, TITLE, ARTIST, ALBUM, COVER, LYRICS)
                assertNotNull("$name 文件源重写失败", rewritten)
                val meta = AudioMetaProbe.read(rewritten!!)
                assertEquals("$name 标题不一致", TITLE, meta.title)
                assertEquals("$name 艺术家不一致", ARTIST, meta.artist)
                assertEquals("$name 专辑不一致", ALBUM, meta.album)
                assertEquals("$name 歌词不一致", LYRICS, meta.lyrics)
                assertArrayEquals("$name 封面不一致", COVER, meta.cover)
                assertArrayEquals(
                    "$name 音频区被改动",
                    AudioMetaProbe.audioRegion(original),
                    AudioMetaProbe.audioRegion(rewritten),
                )

                // 二次写入：只改标题，标签长度变化后躯干起点须随之落位
                work.writeBytes(rewritten)
                val second = MusicMetadataWriter.rewriteFileBytes(work, TITLE_UPDATED, null, null, null, null)
                assertNotNull("$name 二次写入失败", second)
                assertEquals("$name 标题未更新", TITLE_UPDATED, AudioMetaProbe.read(second!!).title)
                assertArrayEquals(
                    "$name 二次写入改动了音频区",
                    AudioMetaProbe.audioRegion(original),
                    AudioMetaProbe.audioRegion(second),
                )
            } finally {
                work.delete()
            }
        }
    }

    /**
     * 超大文件的窗口解析：FLAC 的标签区在头部，元数据之外的尺寸上限不适用于它 ——
     * 超过上限的文件仍须按窗口解析 + 躯干流式搬运完成重写。
     */
    @Test
    fun largeFlacIsRewrittenThroughWindowWithoutWholeFileParse() {
        val source = flacWithStreamInfo(ByteArray(AUDIO_BYTES) { (it % 251).toByte() })
        assertTrue("样本未超过 Ogg 页序列重排上限，用例失去意义", source.size > OGG_PARSE_LIMIT_BYTES)

        val work = File(tempDir(), "large.flac").apply { writeBytes(source) }
        try {
            val rewritten = MusicMetadataWriter.rewriteFileBytes(work, TITLE + LARGE_MARK, null, null, null, null)
            assertNotNull("大文件 FLAC 未重写", rewritten)
            assertEquals("大文件标题未写入", TITLE + LARGE_MARK, AudioMetaProbe.read(rewritten!!).title)
            // 音频躯干整体后移（头部多出注释块），尾部落位一致即说明搬运区间正确、无截断
            val marker = source.copyOfRange(source.size - TAIL_MARKER_BYTES, source.size)
            assertArrayEquals(
                "大文件尾部落位错误",
                marker,
                rewritten.copyOfRange(rewritten.size - TAIL_MARKER_BYTES, rewritten.size),
            )
        } finally {
            work.delete()
        }
    }

    /**
     * 超大文件的区间读写（WAV/AIFF/DSF/APE）：块表只读块头、标签区定点读取、音频体按区间搬运，
     * 故文件大小不再构成重写上限；音频区必须逐字节保持原样。
     */
    @Test
    fun largeLosslessContainersAreRewrittenThroughRangeIo() {
        val audio = ByteArray(AUDIO_BYTES) { (it % 253).toByte() }
        assertLargeRewrite(wav(audio), "large.wav")
        assertLargeRewrite(aiff(audio), "large.aiff")
        assertLargeRewrite(dsf(audio), "large.dsf")
        assertLargeRewrite(ape(audio), "large.ape")
    }

    /**
     * Ogg/Opus 的页序列：标签页数变化后其后每页的序号与 CRC 都要重排，故仍需整条包流；
     * 超过上限即跳过本次重写并记日志，绝不把大文件读进堆。
     */
    @Test
    fun largeOpusIsSkippedWhilePageStreamCannotBeRanged() {
        val source = opusShaped()
        assertTrue("样本未超过页序列重排上限，用例失去意义", source.size > OGG_PARSE_LIMIT_BYTES)

        val work = File(tempDir(), "large.opus").apply { writeBytes(source) }
        try {
            assertNull(
                "超出上限的 Ogg 不应整文件读入重写",
                MusicMetadataWriter.rewriteFileBytes(work, TITLE, null, null, null, null),
            )
        } finally {
            work.delete()
        }
    }

    private fun assertLargeRewrite(source: ByteArray, name: String) {
        assertTrue("$name 样本未超过 Ogg 页序列重排上限，用例失去意义", source.size > OGG_PARSE_LIMIT_BYTES)
        val work = File(tempDir(), name).apply { writeBytes(source) }
        try {
            val rewritten = MusicMetadataWriter.rewriteFileBytes(work, TITLE + LARGE_MARK, null, null, null, null)
            assertNotNull("$name 未重写", rewritten)
            assertEquals("$name 标题未写入", TITLE + LARGE_MARK, AudioMetaProbe.read(rewritten!!).title)
            assertArrayEquals(
                "$name 音频区被改动",
                AudioMetaProbe.audioRegion(source),
                AudioMetaProbe.audioRegion(rewritten),
            )
        } finally {
            work.delete()
        }
    }

    // FLAC 头：marker + 末块标记的 STREAMINFO（34 字节定长载荷）+ 音频帧
    private fun flacWithStreamInfo(audio: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray(Charsets.US_ASCII))
        // 块头：类型 0 且置末块标记，3 字节大端长度
        out.write(byteArrayOf(0x80.toByte(), 0, 0, STREAMINFO_BYTES.toByte()))
        out.write(ByteArray(STREAMINFO_BYTES))
        out.write(audio)
        return out.toByteArray()
    }

    // 最小 RIFF/WAVE：fmt 块 + data 块
    private fun wav(audio: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.write(leInt(RIFF_SIZE_BASE + audio.size))
        out.write("WAVE".toByteArray(Charsets.US_ASCII))
        out.write("fmt ".toByteArray(Charsets.US_ASCII))
        out.write(leInt(FMT_BYTES))
        out.write(ByteArray(FMT_BYTES))
        out.write("data".toByteArray(Charsets.US_ASCII))
        out.write(leInt(audio.size))
        out.write(audio)
        return out.toByteArray()
    }

    // 最小 AIFF：COMM 块 + SSND 音频块（块头为标识 + 大端长度）
    private fun aiff(audio: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("FORM".toByteArray(Charsets.US_ASCII))
        out.write(beInt(AIFF_FORM_SIZE_BASE + audio.size))
        out.write("AIFF".toByteArray(Charsets.US_ASCII))
        out.write("COMM".toByteArray(Charsets.US_ASCII))
        out.write(beInt(COMM_BYTES))
        out.write(ByteArray(COMM_BYTES))
        out.write("SSND".toByteArray(Charsets.US_ASCII))
        out.write(beInt(audio.size))
        out.write(audio)
        return out.toByteArray()
    }

    // 最小 DSF：28 字节文件头 + fmt 块 + data 块；文件头只回填长度与 metadata 指针
    private fun dsf(audio: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val header = ByteArray(DSF_HEADER_BYTES)
        "DSD ".toByteArray(Charsets.US_ASCII).copyInto(header, 0)
        leLong(DSF_HEADER_BYTES.toLong()).copyInto(header, 4)
        leLong((DSF_HEADER_BYTES + DSF_FMT_CHUNK_BYTES + DSF_DATA_HEADER_BYTES + audio.size).toLong()).copyInto(header, 12)
        out.write(header)
        out.write("fmt ".toByteArray(Charsets.US_ASCII))
        out.write(leLong(DSF_FMT_CHUNK_BYTES.toLong()))
        out.write(ByteArray(DSF_FMT_CHUNK_BYTES - DSF_DATA_HEADER_BYTES))
        out.write("data".toByteArray(Charsets.US_ASCII))
        out.write(leLong((DSF_DATA_HEADER_BYTES + audio.size).toLong()))
        out.write(audio)
        return out.toByteArray()
    }

    // 最小 APE：标识 + 头段占位 + 音频体，无既有标签
    private fun ape(audio: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        write("MAC ".toByteArray(Charsets.US_ASCII))
        write(ByteArray(APE_HEADER_PAD))
        write(audio)
    }.toByteArray()

    // Ogg 形状的超大文件：只用于触发页序列重排的尺寸上限，不做结构解析
    private fun opusShaped(): ByteArray = ByteArrayOutputStream().apply {
        write("OggS".toByteArray(Charsets.US_ASCII))
        write(ByteArray(OGG_PAGE_HEADER_PAD))
        write("OpusHead".toByteArray(Charsets.US_ASCII))
        write(ByteArray(AUDIO_BYTES))
    }.toByteArray()

    private fun leInt(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun beInt(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array()

    private fun leLong(value: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()

    private fun tempDir(): File = File(System.getProperty("java.io.tmpdir"), "yichao-metadata-rewrite").apply { mkdirs() }

    private companion object {
        const val FIXTURE_DIR = "src/test/resources/audio/"
        const val TITLE = "探针标题"
        const val TITLE_UPDATED = "探针标题（改）"
        const val ARTIST = "探针艺术家"
        const val ALBUM = "探针专辑"
        const val LYRICS = "[00:01.00]第一行\n[00:03.50]第二行"
        const val LARGE_MARK = "（大文件）"
        const val STREAMINFO_BYTES = 34
        const val FMT_BYTES = 16
        const val RIFF_SIZE_BASE = 36
        const val AIFF_FORM_SIZE_BASE = 38
        const val COMM_BYTES = 18
        const val DSF_HEADER_BYTES = 28
        const val DSF_FMT_CHUNK_BYTES = 52
        const val DSF_DATA_HEADER_BYTES = 12
        const val APE_HEADER_PAD = 4
        const val OGG_PAGE_HEADER_PAD = 28
        const val TAIL_MARKER_BYTES = 64

        // 取自 MusicMetadataWriter.OGG_PARSE_LIMIT_BYTES 的同一口径
        const val OGG_PARSE_LIMIT_BYTES = 32 * 1024 * 1024

        // 音频体大小取「上限 + 余量」：仅为越界验证，不追求更大的样本
        const val AUDIO_BYTES = OGG_PARSE_LIMIT_BYTES + 2 * 1024 * 1024

        // 最小 JPEG 头 + 载荷：与写入端的魔数嗅探口径一致
        val COVER = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(60) { it.toByte() }

        val FIXTURES = listOf(
            "sample.wav", "sample.aiff", "sample.aifc", "sample.flac", "sample.mp3",
            "sample.m4a", "sample.opus", "sample.dsf", "sample.dff", "sample.ape",
        )
    }
}
