package com.yichao.evilgodxu.data.music.metadata

// 写入结果：整曲重建或只重建头部；偏移用 Long
internal sealed interface WriteResult {
    data class Full(val bytes: ByteArray) : WriteResult
    data class HeadAndTail(val head: ByteArray, val audioStart: Long) : WriteResult
    // 躯干按区间搬运，尾部追加元数据
    data class HeadAndRange(val head: ByteArray, val bodyStart: Long, val bodyEnd: Long, val tail: ByteArray) : WriteResult
}

/** 重写方案：头/尾字面字节，躯干按区间搬运 */
internal class RewritePlan(
    val head: ByteArray,
    val bodyStart: Long,
    val bodyEnd: Long,
    val tail: ByteArray,
) {
    // 落到字节数组，躯干按可用长度截断
    fun materialize(body: ByteArray): ByteArray {
        val start = bodyStart.coerceIn(0L, body.size.toLong()).toInt()
        val end = bodyEnd.coerceIn(start.toLong(), body.size.toLong()).toInt()
        return head + body.copyOfRange(start, end) + tail
    }
}

// 容器嗅探窗口，覆盖各容器魔数
internal const val SNIFF_WINDOW_BYTES = 64 * 1024

// 单次重写驻留上限，超限放弃
internal const val MAX_IN_MEMORY_BYTES = 32 * 1024 * 1024

// 读解析窗口；超上限或短读返回 null
internal fun readParseWindow(source: TagSource, offset: Long, size: Long): ByteArray? {
    if (size > MAX_IN_MEMORY_BYTES) return null
    val window = source.readAt(offset, size.toInt()) ?: return null
    return window.takeIf { it.size.toLong() == size }
}

// 整文件结果转重写方案，躯干按区间搬运
internal fun WriteResult.toPlan(source: TagSource): RewritePlan = when (this) {
    is WriteResult.Full -> RewritePlan(bytes, 0L, 0L, ByteArray(0))
    is WriteResult.HeadAndTail -> RewritePlan(head, audioStart, source.size, ByteArray(0))
    is WriteResult.HeadAndRange -> RewritePlan(head, bodyStart, bodyEnd, tail)
}
