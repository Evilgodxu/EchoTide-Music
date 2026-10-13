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
 *   1. 各容器的样本落到真实文件后走文件源重写，逐字段读回并核对音频区未被改动；
 *   2. 二次写入（标签长度变化）后躯干偏移仍须正确；
 *   3. 超大文件：WAV 的块表只读块头，故不受尺寸限制；
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
     * 超大文件的区间读写（WAV）：块表只读块头、标签区定点读取、音频体按区间搬运，
     * 故文件大小不再构成重写上限；音频区必须逐字节保持原样。
     */
    @Test
    fun largeWavIsRewrittenThroughRangeIo() {
        val audio = ByteArray(AUDIO_BYTES) { (it % 253).toByte() }
        assertLargeRewrite(wav(audio), "large.wav")
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

    /**
     * 分片 MP4（DASH 式 moof+mdat 序列）的重写：搬运判据须按「一侧的合计」而不是「单个盒子」。
     *
     * 成百上千组成对的 moof+mdat 每块都不大，合计却可达数百 MB；按整段读进内存会把进程推到
     * 系统内存回收线以下（被系统杀死且不产生崩溃日志）。本用例的尾部块超过单块上限，
     * 只有把尾部按区间搬运才能重写成功。
     */
    @Test
    fun fragmentedMp4IsRewrittenByStreamingTheTrailingRun() {
        val source = fragmentedMp4()
        assertTrue("样本的尾部块未超过单块搬运上限，用例失去意义", BIG_MDAT_BYTES > 32 * 1024 * 1024)

        val work = File(tempDir(), "fragmented.m4a").apply { writeBytes(source) }
        try {
            val rewritten = MusicMetadataWriter.rewriteFileBytes(work, TITLE, ARTIST, ALBUM, null, LYRICS)
            assertNotNull("分片 MP4 未重写（尾部被判为超出搬运上限）", rewritten)
            assertEquals("分片 MP4 标题未写入", TITLE, AudioMetaProbe.read(rewritten!!).title)
            // 尾部落位一致：搬运区间的终点与源一致，说明整段尾部未被截断
            assertArrayEquals(
                "分片 MP4 尾部落位错误",
                source.copyOfRange(source.size - TAIL_MARKER_BYTES, source.size),
                rewritten.copyOfRange(rewritten.size - TAIL_MARKER_BYTES, rewritten.size),
            )
            // 大块内容原样保留：按标记定位后逐点抽查，避免只看文件首尾而漏掉中间错位
            val sourceStart = indexOfAscii(source, "HEAD")
            val rewrittenStart = indexOfAscii(rewritten, "HEAD")
            assertTrue("未在源文件中找到 mdat 起始标记", sourceStart >= 0)
            assertTrue("未在重写结果中找到 mdat 起始标记", rewrittenStart >= 0)
            assertArrayEquals(
                "分片 MP4 的 mdat 内容被改动",
                source.copyOfRange(sourceStart, sourceStart + TAIL_MARKER_BYTES),
                rewritten.copyOfRange(rewrittenStart, rewrittenStart + TAIL_MARKER_BYTES),
            )
        } finally {
            work.delete()
        }
    }

    private fun indexOfAscii(bytes: ByteArray, marker: String): Int {
        val target = marker.toByteArray(Charsets.US_ASCII)
        return (0..(bytes.size - target.size)).firstOrNull { start ->
            target.indices.all { bytes[start + it] == target[it] }
        } ?: -1
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

    // Ogg 形状的超大文件：只用于触发页序列重排的尺寸上限，不做结构解析
    private fun opusShaped(): ByteArray = ByteArrayOutputStream().apply {
        write("OggS".toByteArray(Charsets.US_ASCII))
        write(ByteArray(OGG_PAGE_HEADER_PAD))
        write("OpusHead".toByteArray(Charsets.US_ASCII))
        write(ByteArray(AUDIO_BYTES))
    }.toByteArray()

    // 分片 MP4：ftyp + free + moov(ilst) + moof + 大 mdat + moof + 小 mdat。
    // moov 在最前（faststart），尾部一侧字节更多，故尾部整段按区间搬运
    private fun fragmentedMp4(): ByteArray {
        val ilst = mp4Box("ilst", mp4Box("©nam", mp4DataAtom("原标题")))
        val meta = mp4Box("meta", ByteArray(4) + mp4Box("hdlr", ByteArray(8)) + ilst)
        val moov = mp4Box("moov", mp4Box("mvhd", ByteArray(96)) + mp4Box("udta", meta))
        val bigMdatBody = ByteArray(BIG_MDAT_BYTES) { (it % 251).toByte() }
        // 尾部标记：块首与块末各放一处，验证搬运区间两端都落位正确
        "HEAD".toByteArray(Charsets.US_ASCII).copyInto(bigMdatBody, 0)
        "TAIL".toByteArray(Charsets.US_ASCII).copyInto(bigMdatBody, BIG_MDAT_BYTES - 4)
        return ByteArrayOutputStream().apply {
            write(mp4Box("ftyp", "M4A ".toByteArray(Charsets.ISO_8859_1) + ByteArray(16)))
            write(mp4Box("free", ByteArray(74)))
            write(moov)
            write(mp4Box("moof", ByteArray(1024)))
            write(mp4Box("mdat", bigMdatBody))
            write(mp4Box("moof", ByteArray(1024)))
            write(mp4Box("mdat", ByteArray(64) { 7 }))
        }.toByteArray()
    }

    private fun mp4Box(type: String, body: ByteArray): ByteArray =
        beInt(body.size + 8) + type.toByteArray(Charsets.ISO_8859_1) + body

    // data 子原子：类型/区域/版本标志 16 字节头 + 载荷
    private fun mp4DataAtom(text: String): ByteArray {
        val payload = text.toByteArray(Charsets.UTF_8)
        val body = beInt(1) + ByteArray(4) + payload
        return mp4Box("data", body)
    }

    private fun leInt(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun beInt(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array()

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
        const val OGG_PAGE_HEADER_PAD = 28
        const val TAIL_MARKER_BYTES = 64

        // 取自 MusicMetadataWriter.OGG_PARSE_LIMIT_BYTES 的同一口径
        const val OGG_PARSE_LIMIT_BYTES = 32 * 1024 * 1024

        // 音频体大小取「上限 + 余量」：仅为越界验证，不追求更大的样本
        const val AUDIO_BYTES = OGG_PARSE_LIMIT_BYTES + 2 * 1024 * 1024

        // 分片 MP4 尾部的大块：超过单块搬运上限（写入端的 MAX_IN_MEMORY_BYTES）
        const val BIG_MDAT_BYTES = 34 * 1024 * 1024

        // 最小 JPEG 头 + 载荷：与写入端的魔数嗅探口径一致
        val COVER = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(60) { it.toByte() }

        val FIXTURES = listOf(
            "sample.wav", "sample.flac", "sample.mp3",
            "sample.m4a", "sample.opus",
        )
    }
}
