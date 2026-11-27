package com.yichao.evilgodxu.data.music.metadata

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

// FLAC 块头：1 标志 + 3 长度
private const val FLAC_BLOCK_HEADER_BYTES = 4

// FLAC 重写：头部窗口增量扩窗到末块，音频帧不解析
internal object FlacTagPlan {

    fun plan(
        source: TagSource,
        fields: TrackTagFields,
        cover: ByteArray?,
        removeCover: Boolean,
    ): RewritePlan? {
        var window = source.readAt(0, SNIFF_WINDOW_BYTES) ?: return null
        while (true) {
            val metadataEnd = flacMetadataEnd(window)
            if (metadataEnd != null) {
                return writeFlac(window.copyOf(metadataEnd), fields, cover, removeCover)?.toPlan(source)
            }
            val grown = minOf(window.size.toLong() * 2, MAX_IN_MEMORY_BYTES.toLong()).toInt()
            // 超上限即放弃，避免无限扩窗
            if (grown <= window.size) return null
            window = source.readAt(0, grown) ?: return null
            // 短读说明缺末块，结构不完整
            if (window.size < grown) return null
        }
    }

    // 块头：类型、长度与末块
    private class FlacBlockHeader(val type: Int, val length: Int, val last: Boolean)

    private fun flacBlockHeader(bytes: ByteArray, offset: Int): FlacBlockHeader? {
        if (offset + FLAC_BLOCK_HEADER_BYTES > bytes.size) return null
        val header = bytes[offset].toInt() and 0xff
        val length = (bytes[offset + 1].toInt() and 0xff shl 16) or
            (bytes[offset + 2].toInt() and 0xff shl 8) or (bytes[offset + 3].toInt() and 0xff)
        return FlacBlockHeader(header and 0x7f, length, header and 0x80 != 0)
    }

    // 元数据区结束偏移；未覆盖末块返回 null
    private fun flacMetadataEnd(window: ByteArray): Int? {
        var offset = 4
        while (true) {
            val header = flacBlockHeader(window, offset) ?: return null
            val end = offset + FLAC_BLOCK_HEADER_BYTES + header.length
            if (end > window.size) return null
            if (header.last) return end
            offset = end
        }
    }

    private fun writeFlac(
        source: ByteArray,
        fields: TrackTagFields,
        cover: ByteArray?,
        removeCover: Boolean,
    ): WriteResult? {
        if (!source.startsWith("fLaC")) return null
        val blocks = mutableListOf<FlacBlock>()
        var p = 4
        var hasLastBlock = false
        while (true) {
            val header = flacBlockHeader(source, p) ?: break
            val end = p + FLAC_BLOCK_HEADER_BYTES + header.length
            if (end > source.size) return null
            blocks += FlacBlock(header.type, source.copyOfRange(p + FLAC_BLOCK_HEADER_BYTES, end))
            p = end
            if (header.last) {
                hasLastBlock = true
                break
            }
        }
        if (!hasLastBlock || p > source.size) return null
        if (fields.entries().any { VORBIS_KEYS.containsKey(it.first) }) {
            val commentIndex = blocks.indexOfFirst { it.type == 4 }
            val comments = buildComments(
                if (commentIndex >= 0) blocks[commentIndex].data else null,
                fields,
            )
            if (commentIndex >= 0) blocks[commentIndex] = FlacBlock(4, comments) else blocks.add(FlacBlock(4, comments))
        }
        // 封面改动时先移除既有图片块(3)，再放新图
        if (cover != null || removeCover) {
            blocks.removeAll { block ->
                block.type == 6 && block.data.size >= 4 && int32(block.data, 0) == 3
            }
            if (cover != null) blocks.add(FlacBlock(6, pictureBlock(cover)))
        }
        // 头部为重建块，音频帧按原偏移复制
        return WriteResult.HeadAndTail(buildFlac(blocks), p.toLong())
    }

    private data class FlacBlock(val type: Int, val data: ByteArray)

    private fun buildComments(
        original: ByteArray?,
        fields: TrackTagFields,
    ): ByteArray {
        // 被覆盖的键名，同义键一并去除
        val replacedKeys = mutableSetOf<String>()
        fields.entries().forEach { (field, _) -> VORBIS_KEYS[field]?.let { replacedKeys += it } }
        val vendor: ByteArray
        val values = mutableListOf<String>()
        if (original != null && original.size >= 8) {
            val vendorLength = intLE(original, 0)
            if (vendorLength >= 0 && 8 + vendorLength <= original.size) {
                vendor = original.copyOfRange(4, 4 + vendorLength)
                var p = 8 + vendorLength
                val count = intLE(original, 4 + vendorLength)
                // 字段数以剩余字节为上限，避免损坏文件空转
                repeat(count.coerceAtLeast(0).coerceAtMost((original.size - p).coerceAtLeast(0) / 4)) {
                    if (p + 4 > original.size) return@repeat
                    val length = intLE(original, p); p += 4
                    if (length >= 0 && p + length <= original.size) {
                        val value = String(original, p, length, StandardCharsets.UTF_8)
                        val key = value.substringBefore('=').uppercase()
                        if (key !in replacedKeys) values += value
                        p += length
                    }
                }
            } else return buildComments(null, fields)
        } else vendor = "EdgeGesture".toByteArray()
        fields.entries().forEach { (field, value) -> VORBIS_KEYS[field]?.let { values.add("$it=$value") } }
        val out = ByteArrayOutputStream(); out.write(intBytesLE(vendor.size)); out.write(vendor); out.write(intBytesLE(values.size))
        values.forEach { val value = it.toByteArray(StandardCharsets.UTF_8); out.write(intBytesLE(value.size)); out.write(value) }
        return out.toByteArray()
    }

    private fun buildFlac(blocks: List<FlacBlock>): ByteArray {
        val out = ByteArrayOutputStream(); out.write("fLaC".toByteArray())
        blocks.forEachIndexed { index, block ->
            out.write(byteArrayOf((block.type or if (index == blocks.lastIndex) 0x80 else 0).toByte()))
            out.write(byteArrayOf((block.data.size shr 16).toByte(), (block.data.size shr 8).toByte(), block.data.size.toByte()))
            out.write(block.data)
        }
        return out.toByteArray()
    }
}
