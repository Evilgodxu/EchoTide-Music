package com.yichao.evilgodxu.data.music.playback.alac

/**
 * ALAC 位流读取器，MSB 在前。
 * 越界读取 0 而非残留字节：前缀必遇终止位，不改结果
 * 读残留会让同一条流随前一条数据解出不同结果。
 */
internal class AlacBitReader(private val data: ByteArray, private val length: Int) {

    /** 当前位偏移，可从 0 起任意定位 */
    var position: Int = 0

    /** 位流总位数 */
    val sizeBits: Int = length * BYTE_BITS

    /** 读 count 位（0..32） */
    fun read(count: Int): Int {
        val value = peek(count)
        position += count
        return value
    }

    /** 窥读 count 位（0..32），不推进位置 */
    fun peek(count: Int): Int = bitsAt(position, count)

    /** 跳过 count 位，可为负，用于归还已读位 */
    fun skip(count: Int) {
        position += count
    }

    private fun bitsAt(start: Int, count: Int): Int {
        if (count <= 0) return 0
        require(count <= Int.SIZE_BITS) { "一次最多读 32 位，请求 $count 位" }
        var result = 0L
        var cursor = start
        var remaining = count
        while (remaining > 0) {
            val byteIndex = cursor shr BYTE_BITS_SHIFT
            val bitOffset = cursor and BYTE_BIT_MASK
            val available = BYTE_BITS - bitOffset
            val take = if (available < remaining) available else remaining
            val current = if (byteIndex >= 0 && byteIndex < length) {
                data[byteIndex].toInt() and 0xFF
            } else {
                0
            }
            result = (result shl take) or
                ((current ushr (available - take)) and ((1 shl take) - 1)).toLong()
            cursor += take
            remaining -= take
        }
        return result.toInt()
    }

    private companion object {
        const val BYTE_BITS = 8
        const val BYTE_BITS_SHIFT = 3
        const val BYTE_BIT_MASK = 7
    }
}
