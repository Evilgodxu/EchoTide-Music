package com.yichao.evilgodxu.data.music.metadata

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内嵌歌词容器解析复核：重点覆盖 M4A 的 data 原子解码——歌词原子的长度字段描述的是
 * 「原子自身总长」，载荷长度须再减去原子头 16 字节（类型 4 + 区域设置 4 + 版本与标志 8），
 * 把「偏移 + 长度」当成载荷长度传入会让解码越过缓冲区。
 * 合成样本按 MP4/FLAC 规范排布，不依赖被测代码的写入结果。
 */
class MusicEmbeddedLyricReaderTest {

    private val lyrics = "[00:01.00]第一行\n[00:03.50]第二行"

    @Test
    fun mp4LyricsAtomIsDecodedWithinHeaderWindow() {
        // 与实测样本一致的规模：歌词原子落在 512KB 头窗的后段，其 data 子原子声明的长度
        // 覆盖到窗口外——按偏移当长度解码时区间起点 463908、长度 486236，必然越界
        val payload = lyrics + " ".repeat(DATA_ATOM_PAYLOAD_BYTES - lyrics.toByteArray(StandardCharsets.UTF_8).size)
        val buffer = mp4WithLyricsAt(LYRICS_ATOM_OFFSET, payload)
        assertEquals(HEADER_WINDOW_BYTES, buffer.size)
        assertEquals(payload, MusicEmbeddedLyricReader.extractLyrics(buffer))
        assertTrue(MusicEmbeddedLyricReader.extractLyrics(buffer)!!.startsWith(lyrics))
    }

    @Test
    fun mp4LyricsAtomWithShortDataAtomIsRejected() {
        // data 子原子只声明 8 字节、容纳不下版本与区域设置字段：视为损坏，不得解码出脏数据
        val buffer = ByteArray(64)
        ftypAtom(16).copyInto(buffer, 0)
        atom("©lyr", int32BE(8) + "data".toByteArray(StandardCharsets.ISO_8859_1)).copyInto(buffer, 16)
        assertNull(MusicEmbeddedLyricReader.extractLyrics(buffer))
    }

    @Test
    fun flacVorbisCommentLyricsIsDecoded() {
        val comment = ByteArrayOutputStream()
        val vendor = "EdgeGesture".toByteArray(StandardCharsets.UTF_8)
        val fields = listOf("TITLE=标题", "LYRICS=$lyrics")
        comment.write(intLE(vendor.size)); comment.write(vendor); comment.write(intLE(fields.size))
        fields.forEach {
            val value = it.toByteArray(StandardCharsets.UTF_8)
            comment.write(intLE(value.size)); comment.write(value)
        }
        val body = comment.toByteArray()
        val length = byteArrayOf((body.size shr 16).toByte(), (body.size shr 8).toByte(), body.size.toByte())
        val block = byteArrayOf(0x84.toByte()) + length + body
        assertEquals(lyrics, MusicEmbeddedLyricReader.extractLyrics("fLaC".toByteArray() + block))
    }

    // 512KB 头窗内的 MP4：ftyp 原子占位到 lyricsAtomOffset 处，其后的 ©lyr 原子承载歌词，
    // data 子原子紧随其后，故子原子起点为 lyricsAtomOffset + 8
    private fun mp4WithLyricsAt(lyricsAtomOffset: Int, payload: String): ByteArray {
        val buffer = ByteArray(HEADER_WINDOW_BYTES)
        ftypAtom(lyricsAtomOffset).copyInto(buffer, 0)
        atom("©lyr", dataAtom(payload)).copyInto(buffer, lyricsAtomOffset)
        return buffer
    }

    private fun ftypAtom(size: Int): ByteArray =
        int32BE(size) + "ftyp".toByteArray(StandardCharsets.ISO_8859_1) + ByteArray(size - 8)

    private fun atom(type: String, body: ByteArray): ByteArray =
        int32BE(body.size + 8) + type.toByteArray(StandardCharsets.ISO_8859_1) + body

    // data 子原子：长度(4) + "data"(4) + 版本与标志(4) + 区域设置(4) + 载荷
    private fun dataAtom(payload: String): ByteArray {
        val body = ByteArray(8) + payload.toByteArray(StandardCharsets.UTF_8)
        return int32BE(body.size + 8) + "data".toByteArray(StandardCharsets.ISO_8859_1) + body
    }

    private fun int32BE(value: Int): ByteArray =
        byteArrayOf((value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte())

    private fun intLE(value: Int): ByteArray =
        byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())

    private companion object {
        // 读取端 512KB 头窗
        const val HEADER_WINDOW_BYTES = 512 * 1024
        // ©lyr 原子起点：使 data 子原子落在 463892，载荷起点即日志中越界的 463908
        const val LYRICS_ATOM_OFFSET = 463_884
        // data 子原子的载荷长度：22344 字节，占据 463908..486252
        const val DATA_ATOM_PAYLOAD_BYTES = 22_344
    }
}
