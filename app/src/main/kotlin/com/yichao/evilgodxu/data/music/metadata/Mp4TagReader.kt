package com.yichao.evilgodxu.data.music.metadata

import java.nio.charset.StandardCharsets

/** 逐层下钻 moov→udta→meta→ilst 读取标签原子。
 *  文本原子常在封面后，走滑动窗口只读命中的载荷。 */
internal object Mp4TagReader {

    // 滑动窗口：连续小原子共用一次读取
    private const val WINDOW_BYTES = 32 * 1024

    // 单原子载荷上限，超出按非文本跳过
    private const val MAX_ATOM_BYTES = 1 * 1024 * 1024

    // meta 子原子前有 4 字节版本与标志
    private const val META_HEADER_BYTES = 4

    // 盒子头：普通 8，大尺寸 16
    private const val BOX_HEADER_BYTES = 8
    private const val LARGE_BOX_HEADER_BYTES = 16
    private const val LARGE_SIZE_MARKER = 1L
    private const val TO_END_OF_PARENT = 0L

    // 单层盒子数上限，挡畸形文件
    private const val MAX_BOXES_PER_LEVEL = 4096

    // data 头：类型4 + 区域4 + 版本8
    private const val DATA_ATOM_HEADER_BYTES = 16

    // 需取值的原子：文本(含 ©lyr)、自由格式、lyr
    private val INTERESTING_TYPES: Set<String> =
        (Mp4TagSchema.ATOMS.values + Mp4TagSchema.FREE_FORM_ATOM + "lyr").toSet()

    /** ilst 下的标签原子：类型与载荷 */
    class Atom(val type: String, val payload: ByteArray)

    /** 读取 ilst 全量文本字段；无标签结构返回 null */
    fun readFields(fileSize: Long?, readAt: (Long, Int) -> ByteArray?): TrackTagFields? {
        val result = LinkedHashMap<TagField, String>()
        val freeform = LinkedHashMap<String, String>()
        forEachAtom(fileSize, readAt) { atom ->
            when (atom.type) {
                Mp4TagSchema.FREE_FORM_ATOM ->
                    readFreeform(atom.payload, 0, atom.payload.size)
                        ?.let { (key, value) -> freeform[key] = value }

                else -> Mp4TagSchema.ATOMS.entries
                    .firstOrNull { it.value == atom.type }
                    ?.let { entry ->
                        readTextValue(entry.key, atom.payload)
                            ?.let { value -> if (entry.key !in result) result[entry.key] = value }
                    }
            }
            true
        }
        Mp4TagSchema.FREEFORM_KEYS.forEach { (field, key) ->
            freeform[key]?.let { if (field !in result) result[field] = it }
        }
        return result.takeIf { it.isNotEmpty() }?.let { TrackTagFields.build { it.forEach { (f, v) -> put(f, v) } } }
    }

    /** 遍历 ilst 标签原子，visit 返回 false 即停 */
    fun forEachAtom(fileSize: Long?, readAt: (Long, Int) -> ByteArray?, visit: (Atom) -> Boolean) {
        val cursor = Cursor(fileSize ?: Long.MAX_VALUE, readAt)
        val ilst = locateIlst(cursor) ?: return
        cursor.forEachBox(ilst.first, ilst.second) { type, payloadStart, payloadEnd ->
            if (type in INTERESTING_TYPES) {
                val length = payloadEnd - payloadStart
                if (length in 1..MAX_ATOM_BYTES.toLong()) {
                    cursor.read(payloadStart, length.toInt())?.let { payload ->
                        if (!visit(Atom(type, payload))) return@forEachBox false
                    }
                }
            }
            true
        }
    }

    // 文本原子取值：音轨号/碟号为二进制
    private fun readTextValue(field: TagField, payload: ByteArray): String? {
        val data = readDataPayload(payload, 0, payload.size) ?: return null
        return if (field == TagField.TRACK_NUMBER || field == TagField.DISC_NUMBER) {
            trackNumber(data)
        } else {
            String(data, StandardCharsets.UTF_8).takeIf { it.isNotBlank() }
        }
    }

    // data 载荷在 16 字节头之后
    private fun readDataPayload(bytes: ByteArray, start: Int, end: Int): ByteArray? {
        var p = start
        while (p + BOX_HEADER_BYTES <= end) {
            val size = int32BE(bytes, p)
            if (size < BOX_HEADER_BYTES || p + size > end) return null
            if (String(bytes, p + 4, 4, StandardCharsets.ISO_8859_1) == "data") {
                if (size <= DATA_ATOM_HEADER_BYTES) return null
                return bytes.copyOfRange(p + DATA_ATOM_HEADER_BYTES, p + size)
            }
            p += size
        }
        return null
    }

    // 音轨号载荷：2 保留 + 编号(2) + 总数(2)
    private fun trackNumber(payload: ByteArray): String? {
        if (payload.size < 4) return null
        val number = ((payload[2].toInt() and 0xFF) shl 8) or (payload[3].toInt() and 0xFF)
        if (number <= 0) return null
        val total = if (payload.size >= 6) ((payload[4].toInt() and 0xFF) shl 8) or (payload[5].toInt() and 0xFF) else 0
        return if (total > 0) "$number/$total" else number.toString()
    }

    // 自由格式：name 给键名，data 给值
    private fun readFreeform(bytes: ByteArray, start: Int, end: Int): Pair<String, String>? {
        var key: String? = null
        var value: String? = null
        var p = start
        while (p + BOX_HEADER_BYTES <= end) {
            val size = int32BE(bytes, p)
            if (size < BOX_HEADER_BYTES || p + size > end) return null
            when (String(bytes, p + 4, 4, StandardCharsets.ISO_8859_1)) {
                "name" -> if (size > 12) key = String(bytes, p + 12, size - 12, StandardCharsets.UTF_8)
                "data" -> if (size > DATA_ATOM_HEADER_BYTES) {
                    value = String(bytes, p + DATA_ATOM_HEADER_BYTES, size - DATA_ATOM_HEADER_BYTES, StandardCharsets.UTF_8)
                }
            }
            p += size
        }
        return if (key != null && value != null && value.isNotBlank()) key to value else null
    }

    private fun int32BE(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF shl 24) or (bytes[at + 1].toInt() and 0xFF shl 16) or
            (bytes[at + 2].toInt() and 0xFF shl 8) or (bytes[at + 3].toInt() and 0xFF)

    // 下钻 moov→udta→meta→ilst
    private fun locateIlst(cursor: Cursor): Pair<Long, Long>? {
        val moov = cursor.findBox(0L, cursor.fileSize, "moov") ?: return null
        val udta = cursor.findBox(moov.first, moov.second, "udta")
        val meta = if (udta != null) {
            cursor.findBox(udta.first, udta.second, "meta")
        } else {
            cursor.findBox(moov.first, moov.second, "meta")
        } ?: return null
        return cursor.findBox(meta.first + META_HEADER_BYTES, meta.second, "ilst")
    }

    /** 按绝对偏移遍历盒子的游标，越过大原子只跳到末尾 */
    private class Cursor(val fileSize: Long, private val readAt: (Long, Int) -> ByteArray?) {
        private var windowStart = -1L
        private var window = ByteArray(0)

        /** 查找首个指定类型的盒子，返回其载荷区间 */
        fun findBox(from: Long, to: Long, type: String): Pair<Long, Long>? {
            var found: Pair<Long, Long>? = null
            forEachBox(from, to) { boxType, payloadStart, payloadEnd ->
                if (boxType == type) {
                    found = payloadStart to payloadEnd
                    false
                } else {
                    true
                }
            }
            return found
        }

        /** 遍历 [from, to) 内的盒子；[visit] 返回 false 即停止 */
        fun forEachBox(from: Long, to: Long, visit: (String, Long, Long) -> Boolean) {
            var position = from
            var visited = 0
            while (position + BOX_HEADER_BYTES <= to && visited++ < MAX_BOXES_PER_LEVEL) {
                val head = read(position, BOX_HEADER_BYTES) ?: return
                val declared = int32BE(head, 0).toLong() and 0xFFFFFFFFL
                val header: Long
                val size: Long
                when (declared) {
                    LARGE_SIZE_MARKER -> {
                        val large = read(position, LARGE_BOX_HEADER_BYTES) ?: return
                        size = int64BE(large, BOX_HEADER_BYTES)
                        header = LARGE_BOX_HEADER_BYTES.toLong()
                    }
                    // 长度为 0 表示延伸到父级末尾
                    TO_END_OF_PARENT -> {
                        size = to - position
                        header = BOX_HEADER_BYTES.toLong()
                    }
                    else -> {
                        size = declared
                        header = BOX_HEADER_BYTES.toLong()
                    }
                }
                if (size < header || position + size > to) return
                val type = String(head, 4, 4, StandardCharsets.ISO_8859_1)
                if (!visit(type, position + header, position + size)) return
                position += size
            }
        }

        /** 读 [offset, offset+count)；越界或不足返回 null */
        fun read(offset: Long, count: Int): ByteArray? {
            if (count <= 0 || offset < 0L || offset + count > fileSize) return null
            val cached = sliceWindow(offset, count)
            if (cached != null) return cached
            if (count >= WINDOW_BYTES / 2) {
                val block = readAt(offset, count) ?: return null
                return block.takeIf { it.size == count }
            }
            val want = minOf(WINDOW_BYTES.toLong(), fileSize - offset).toInt()
            val chunk = readAt(offset, want) ?: return null
            if (chunk.size < count) return null
            windowStart = offset
            window = chunk
            return chunk.copyOf(count)
        }

        private fun sliceWindow(offset: Long, count: Int): ByteArray? {
            if (windowStart < 0L) return null
            if (offset < windowStart || offset + count > windowStart + window.size) return null
            val from = (offset - windowStart).toInt()
            return window.copyOfRange(from, from + count)
        }

        private fun int32BE(bytes: ByteArray, at: Int): Int =
            (bytes[at].toInt() and 0xFF shl 24) or (bytes[at + 1].toInt() and 0xFF shl 16) or
                (bytes[at + 2].toInt() and 0xFF shl 8) or (bytes[at + 3].toInt() and 0xFF)

        private fun int64BE(bytes: ByteArray, at: Int): Long {
            var value = 0L
            for (index in 0 until 8) value = (value shl 8) or (bytes[at + index].toLong() and 0xFF)
            return value
        }
    }
}
