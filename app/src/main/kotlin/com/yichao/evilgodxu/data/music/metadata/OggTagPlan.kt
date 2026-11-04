package com.yichao.evilgodxu.data.music.metadata

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

// Ogg 页序列重排上限：标签页数变化后需逐页重编号与重算 CRC，故须持有整条包流；
// 超过该上限即跳过本次重写并记日志
private const val OGG_PARSE_LIMIT_BYTES = 32L * 1024 * 1024

// Ogg 单页段数上限：段表为单字节长度数组，一个页面最多承载 255 段
private const val MAX_SEGMENTS = 255

// Ogg 页头中 CRC 字段的偏移（页头 27 字节内）
private const val CRC_FIELD_OFFSET = 22

// 注释包的两种包头：Opus 为包标识 "OpusTags"，Vorbis 为包类型 3 + "vorbis"。
// 两者载荷结构一致（LE 长度的 vendor + 字段数 + 「KEY=value」字段），差别只在包头与结尾
private const val OPUS_COMMENT_HEADER = "OpusTags"
private const val VORBIS_COMMENT_HEADER = "\u0003vorbis"

// Vorbis 的注释头包以框架位（1 位，恒为 1）结束，占末尾一个字节；缺它解码器判流损坏
private const val VORBIS_FRAMING_BYTE = 1

/**
 * Ogg（Opus 与 Vorbis）标签重写：标签包与音频包同处一条页序列，标签长度变化会改变标签页数，
 * 其后每页的序号与 CRC 都要随之重排，故需要整条包流才能定位。
 * 超过 [OGG_PARSE_LIMIT_BYTES] 即跳过本次重写，不把大文件读进堆。
 */
internal object OggTagPlan {

    fun plan(
        source: TagSource,
        fields: TrackTagFields,
        cover: ByteArray?,
        removeCover: Boolean,
    ): RewritePlan? {
        if (source.size > OGG_PARSE_LIMIT_BYTES) return null
        val bytes = source.readAt(0, source.size.toInt()) ?: return null
        if (bytes.size.toLong() < source.size) return null
        return writeOggComments(bytes, fields, cover, removeCover)?.toPlan(ByteArrayTagSource(bytes))
    }

    private fun writeOggComments(
        source: ByteArray,
        fields: TrackTagFields,
        cover: ByteArray?,
        removeCover: Boolean,
    ): WriteResult? {
        val parsed = OggFile.parse(source) ?: return null
        val commentsIndex = parsed.packets.indexOfFirst { it.data.isOggCommentPacket() }
        if (commentsIndex < 0) return null
        parsed.packets[commentsIndex] = parsed.packets[commentsIndex].copy(
            data = updateCommentPacket(parsed.packets[commentsIndex].data, fields, cover, removeCover),
        )
        return WriteResult.Full(OggFile.build(parsed))
    }

    private fun ByteArray.isOggCommentPacket(): Boolean =
        startsWith(OPUS_COMMENT_HEADER) || startsWith(VORBIS_COMMENT_HEADER)

    private fun updateCommentPacket(
        original: ByteArray,
        fields: TrackTagFields,
        cover: ByteArray?,
        removeCover: Boolean,
    ): ByteArray {
        val opus = original.startsWith(OPUS_COMMENT_HEADER)
        val header = if (opus) OPUS_COMMENT_HEADER else VORBIS_COMMENT_HEADER
        val replacedKeys = mutableSetOf<String>()
        fields.entries().forEach { (field, _) -> VORBIS_KEYS[field]?.let { replacedKeys += it } }
        if (cover != null || removeCover) replacedKeys += "METADATA_BLOCK_PICTURE"
        val values = mutableListOf<String>(); var p = header.length
        var vendor = "EdgeGesture".toByteArray()
        if (p + 4 <= original.size) {
            val vendorLength = intLE(original, p)
            if (vendorLength < 0 || p + 4 + vendorLength > original.size) return original
            vendor = original.copyOfRange(p + 4, p + 4 + vendorLength)
            p += 4 + vendorLength
            if (p + 4 <= original.size) {
                val count = intLE(original, p); p += 4
                // 同上：字段数不得越过剩余字节能承载的上限
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
            }
        }
        fields.entries().forEach { (field, value) -> VORBIS_KEYS[field]?.let { values += "$it=$value" } }
        if (cover != null) values += "METADATA_BLOCK_PICTURE=" + java.util.Base64.getEncoder().encodeToString(pictureBlock(cover))
        val out = ByteArrayOutputStream()
        out.write(header.toByteArray())
        out.write(intBytesLE(vendor.size)); out.write(vendor); out.write(intBytesLE(values.size))
        values.forEach { val bytes = it.toByteArray(); out.write(intBytesLE(bytes.size)); out.write(bytes) }
        if (!opus) out.write(VORBIS_FRAMING_BYTE)
        return out.toByteArray()
    }

    // endPage 为包结束所在的原页面序号：改写只动标签包的内容，页面归属须原样保留
    private data class OggPacket(var data: ByteArray, val granulePosition: Long, val endPage: Int)

    private data class OggPage(
        val headerType: Int,
        val granulePosition: Long,
        val serial: Int,
        val sequence: Int,
        val segmentCount: Int,
    )

    private data class OggFile(
        val packets: MutableList<OggPacket>,
        val serial: Int,
        val firstSequence: Int,
        val firstHeaderType: Int,
    ) {
        companion object {
            fun parse(bytes: ByteArray): OggFile? {
                val pages = mutableListOf<OggPage>()
                val packets = mutableListOf<OggPacket>()
                val packet = ByteArrayOutputStream()
                var p = 0
                var serial: Int? = null
                var expectedSequence: Long? = null
                while (p + 27 <= bytes.size) {
                    if (String(bytes, p, 4, StandardCharsets.US_ASCII) != "OggS" || bytes[p + 4].toInt() != 0) return null
                    val headerType = bytes[p + 5].toInt() and 0xff
                    val granule = longLE(bytes, p + 6)
                    val pageSerial = intLE(bytes, p + 14)
                    val sequence = intLE(bytes, p + 18)
                    val segmentCount = bytes[p + 26].toInt() and 0xff
                    val lacingEnd = p + 27 + segmentCount
                    if (lacingEnd > bytes.size) return null
                    val bodyLength = (0 until segmentCount).sumOf { bytes[p + 27 + it].toInt() and 0xff }
                    val pageEnd = lacingEnd + bodyLength
                    if (pageEnd > bytes.size || (headerType and 1 != 0) != (packet.size() != 0)) return null
                    if (serial == null) serial = pageSerial
                    if (serial != pageSerial || (expectedSequence != null && expectedSequence != sequence.toLong())) return null
                    expectedSequence = sequence.toLong() + 1
                    pages += OggPage(headerType, granule, pageSerial, sequence, segmentCount)
                    var bodyOffset = lacingEnd
                    for (index in 0 until segmentCount) {
                        val length = bytes[p + 27 + index].toInt() and 0xff
                        packet.write(bytes, bodyOffset, length)
                        bodyOffset += length
                        // granule 记录的是「包结束所在页面」的位置：Ogg 把页的 granule 归于
                        // 该页内最后一个完整包，标签包的 granule 因而是 0
                        if (length < 255) {
                            packets += OggPacket(packet.toByteArray(), granule, pages.lastIndex)
                            packet.reset()
                        }
                    }
                    p = pageEnd
                }
                if (p != bytes.size || packet.size() != 0 || pages.isEmpty()) return null
                return OggFile(packets, serial!!, pages.first().sequence, pages.first().headerType)
            }

            fun build(file: OggFile): ByteArray {
                val pages = mutableListOf<ByteArray>()
                var granule = 0L
                var continued = false
                var packetIndex = 0
                // 按「包 → 原页面」归属成页：标签包长度变化只改变该页的段数，
                // 不会把后续音频包吸到头包所在的页上——Ogg Opus 要求头两页 granule 为 0
                while (packetIndex < file.packets.size) {
                    val endPage = file.packets[packetIndex].endPage
                    val group = mutableListOf<OggPacket>()
                    while (packetIndex < file.packets.size && file.packets[packetIndex].endPage == endPage) {
                        group += file.packets[packetIndex]
                        packetIndex++
                    }
                    val units = lacingOf(group)
                    var offset = 0
                    while (offset < units.size) {
                        val count = minOf(MAX_SEGMENTS, units.size - offset)
                        val lacing = units.subList(offset, offset + count)
                        val completed = lacing.lastOrNull { it.packet >= 0 }
                        val pageGranule = if (completed != null) group[completed.packet].granulePosition else granule
                        val headerType = (if (continued) 1 else 0) or
                            (if (pages.isEmpty() && file.firstHeaderType and 2 != 0) 2 else 0)
                        pages += buildPage(headerType, pageGranule, file.serial, file.firstSequence + pages.size, lacing)
                        // 末段不是包末即包跨页，下一页须标记续包
                        continued = lacing.last().packet < 0
                        granule = pageGranule
                        offset += count
                    }
                }
                if (pages.isNotEmpty()) {
                    val last = pages.last()
                    last[5] = (last[5].toInt() and 0xff or 4).toByte()
                    patchCrc(last)
                }
                return pages.fold(ByteArrayOutputStream()) { out, page -> out.apply { write(page) } }.toByteArray()
            }

            // 段表：每 255 字节一段；包长度为 255 的整数倍（含 0）时补一个空段标记包结束
            private fun lacingOf(packets: List<OggPacket>): List<LacingUnit> {
                val units = mutableListOf<LacingUnit>()
                packets.forEachIndexed { index, packet ->
                    var offset = 0
                    while (offset < packet.data.size) {
                        val length = minOf(255, packet.data.size - offset)
                        val endsPacket = offset + length == packet.data.size && length < 255
                        units += LacingUnit(packet.data.copyOfRange(offset, offset + length), if (endsPacket) index else -1)
                        offset += length
                    }
                    if (packet.data.isEmpty() || packet.data.size % 255 == 0) units += LacingUnit(ByteArray(0), index)
                }
                return units
            }

            private fun buildPage(
                headerType: Int,
                granule: Long,
                serial: Int,
                sequence: Int,
                lacing: List<LacingUnit>,
            ): ByteArray {
                val page = ByteArrayOutputStream()
                page.write("OggS".toByteArray(StandardCharsets.US_ASCII)); page.write(0); page.write(headerType)
                page.write(longBytesLE(granule)); page.write(intBytesLE(serial)); page.write(intBytesLE(sequence))
                page.write(intBytesLE(0)); page.write(lacing.size)
                lacing.forEach { page.write(it.bytes.size) }
                lacing.forEach { page.write(it.bytes) }
                val result = page.toByteArray()
                patchCrc(result)
                return result
            }

            private fun patchCrc(page: ByteArray) {
                val crc = oggCrc(page)
                page[22] = crc.toByte(); page[23] = (crc shr 8).toByte()
                page[24] = (crc shr 16).toByte(); page[25] = (crc shr 24).toByte()
            }

            // 段与其所属包在成页分组内的序号；-1 表示该段不是包末段
            private data class LacingUnit(val bytes: ByteArray, val packet: Int)
        }
    }

    // Ogg 页面校验和：多项式 0x04C11DB7，起始值 0、不反转、不异或。
    // 计算时 CRC 字段按零参与——跳过这 4 字节等于少做 32 次移位，算出的校验和必然无效，
    // 而 Ogg 解码器会校验每页 CRC，无效页会让整个流无法打开
    private fun oggCrc(bytes: ByteArray): Int {
        var crc = 0
        bytes.forEachIndexed { index, value ->
            val octet = if (index in CRC_FIELD_OFFSET until CRC_FIELD_OFFSET + 4) 0 else value.toInt() and 0xff
            crc = crc xor (octet shl 24)
            repeat(8) {
                crc = if (crc and 0x80000000.toInt() != 0) (crc shl 1) xor 0x04c11db7 else crc shl 1
            }
        }
        return crc
    }
}
