package com.yichao.evilgodxu.data.music.metadata

import java.nio.charset.StandardCharsets

/**
 * M4A 标签的读取：按盒子头逐层下钻 moov → udta → meta → ilst，再逐个取标签原子的值。
 *
 * 之所以不能按「固定头窗内解析」来做：文本标签原子常排在封面原子之后，而 covr 封面可达数 MB
 * （实测一枚 3000×3000 的封面即 1.1MB，标签原子全部落在其后）。固定头窗遇到超出窗口的盒子就会
 * 判定结构不完整，于是整份标签一个字段都读不到，编辑页打开是空表单——用户把空值存回去就等于
 * 抹掉文件里的既有标签。
 *
 * 本读取器不把头窗读进内存：每层只读盒子头，命中需要取值的原子才读其载荷，封面原子只读头不读体。
 * 读取走滑动窗口——连续的小原子共用一次读取，载荷越过窗口即跳到该原子末尾另起一段，
 * 故读取次数只与「跨过的大原子个数」成正比，读取量始终只覆盖盒子头加命中原子的载荷。
 *
 * 只依赖「按绝对偏移读取」这一件事，与文件 IO 解耦：线上经 LocalAudioSource 读取，
 * 单测直接喂内存字节。
 */
internal object Mp4TagReader {

    // 滑动窗口：连续小原子共用一次读取；单次读取量上限
    private const val WINDOW_BYTES = 32 * 1024

    // 单个标签原子的载荷上限：文本与歌词远小于此，超出即按非文本原子跳过，不读进内存
    private const val MAX_ATOM_BYTES = 1 * 1024 * 1024

    // meta 原子在子原子之前另有 4 字节版本与标志
    private const val META_HEADER_BYTES = 4

    // 盒子头长度：普通 8 字节（4 长度 + 4 类型），大尺寸为 16 字节
    private const val BOX_HEADER_BYTES = 8
    private const val LARGE_BOX_HEADER_BYTES = 16
    private const val LARGE_SIZE_MARKER = 1L
    private const val TO_END_OF_PARENT = 0L

    // 单层遍历的盒子数上限：挡住畸形文件把遍历拖成死循环
    private const val MAX_BOXES_PER_LEVEL = 4096

    // data 子原子的固定头长度：类型(4) + 区域设置(4) + 版本与标志(8)
    private const val DATA_ATOM_HEADER_BYTES = 16

    // 需要取值的原子类型：普通文本原子（含 ©lyr）、自由格式原子（----，其键名在子原子中），
    // 以及非 © 前缀的歌词原子 lyr
    private val INTERESTING_TYPES: Set<String> =
        (MusicMetadataWriter.MP4_ATOMS.values + MusicMetadataWriter.FREE_FORM_ATOM + "lyr").toSet()

    /** ilst 下的一个标签原子：类型与载荷（不含盒子头） */
    class Atom(val type: String, val payload: ByteArray)

    /**
     * 读取 ilst 下的全量文本字段；文件含不了标签结构时返回 null。
     * 普通文本原子按键名取值，音轨号/碟号为二进制载荷，其余字段以 ---- 自由格式原子承载
     */
    fun readFields(fileSize: Long?, readAt: (Long, Int) -> ByteArray?): TrackTagFields? {
        val result = LinkedHashMap<TagField, String>()
        val freeform = LinkedHashMap<String, String>()
        forEachAtom(fileSize, readAt) { atom ->
            when (atom.type) {
                MusicMetadataWriter.FREE_FORM_ATOM ->
                    readFreeform(atom.payload, 0, atom.payload.size)
                        ?.let { (key, value) -> freeform[key] = value }

                else -> MusicMetadataWriter.MP4_ATOMS.entries
                    .firstOrNull { it.value == atom.type }
                    ?.let { entry ->
                        readTextValue(entry.key, atom.payload)
                            ?.let { value -> if (entry.key !in result) result[entry.key] = value }
                    }
            }
            true
        }
        MusicMetadataWriter.MP4_FREEFORM_KEYS.forEach { (field, key) ->
            freeform[key]?.let { if (field !in result) result[field] = it }
        }
        return result.takeIf { it.isNotEmpty() }?.let { TrackTagFields.build { it.forEach { (f, v) -> put(f, v) } } }
    }

    /**
     * 遍历 ilst 下的标签原子；[visit] 返回 false 即停止后续遍历。
     * 文件不含 moov/udta/meta/ilst 结构时不做任何回调。
     * [fileSize] 取不到时按「未知长度」处理，越界交由读取本身判定
     */
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

    // 单个文本原子的取值：音轨号/碟号是二进制载荷，其余是 UTF-8 文本
    private fun readTextValue(field: TagField, payload: ByteArray): String? {
        val data = readDataPayload(payload, 0, payload.size) ?: return null
        return if (field == TagField.TRACK_NUMBER || field == TagField.DISC_NUMBER) {
            trackNumber(data)
        } else {
            String(data, StandardCharsets.UTF_8).takeIf { it.isNotBlank() }
        }
    }

    // data 子原子的载荷：头 16 字节（4 类型 + 4 区域 + 8 版本标志）之后为数据
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

    // 音轨号/碟号的二进制载荷：2 字节保留 + 编号(2) + 总数(2)，编号为 0 时视为未写入
    private fun trackNumber(payload: ByteArray): String? {
        if (payload.size < 4) return null
        val number = ((payload[2].toInt() and 0xFF) shl 8) or (payload[3].toInt() and 0xFF)
        if (number <= 0) return null
        val total = if (payload.size >= 6) ((payload[4].toInt() and 0xFF) shl 8) or (payload[5].toInt() and 0xFF) else 0
        return if (total > 0) "$number/$total" else number.toString()
    }

    // 自由格式原子：name 子原子给出键名，data 子原子给出值
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

    // moov/udta/meta/ilst；极少数工具直接写 moov/meta/ilst，一并支持
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

    /**
     * 按盒子头遍历字节区间的游标：滑动窗口缓存 + 越窗跳段。
     *
     * 区间一律用「绝对文件偏移」表达，故越过封面这类大原子时只需把游标移到该原子末尾，
     * 中间那段封面数据永远不会被读取。
     */
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

        /**
         * 读取 [offset, offset + count)：命中窗口即切自缓存，大块直接定点读取。
         * 超出文件范围或读取不足时返回 null，由调用方按结构不成立处理
         */
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
