package com.yichao.evilgodxu.data.music.playback.alac

/**
 * ALAC 位流的读取器，MSB 在前。
 *
 * [length] 之外的读取一律取 0 而不是报错，也不要读到缓冲区里残留的上一条数据：参考实现的比特缓冲按字节
 * 指针推进，读一个字段时会连带取到后续两三个字节，帧尾那几个字段的读取本就会越过样本末尾。越界字节
 * 不参与结果——前缀计数（连续 1 的个数）必然在帧内遇到终止位，定长字段也整段落在帧内——故取 0 与
 * 参考实现在结果上等价；取残留数据反而会让同一条流随前一条数据的不同解出不同结果。
 */
internal class AlacBitReader(private val data: ByteArray, private val length: Int) {

    /** 当前位偏移，可从 0 起任意定位 */
    var position: Int = 0

    /** 位流总位数 */
    val sizeBits: Int = length * BYTE_BITS

    /** 读 [count] 位（0..32） */
    fun read(count: Int): Int {
        val value = peek(count)
        position += count
        return value
    }

    /** 窥读 [count] 位（0..32），不推进位置 */
    fun peek(count: Int): Int = bitsAt(position, count)

    /** 跳过 [count] 位，可为负（用于把已读的位归还） */
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
