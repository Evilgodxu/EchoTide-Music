package com.yichao.evilgodxu.data.music.metadata

// 元数据写入结果：Full 为整曲重建；HeadAndTail 仅重建头部标签，
// 音频躯干按 audioStart 偏移从源复制，降低大文件写入的峰值内存。
// 区间偏移用 Long：高解析无损单文件可越过 2GB 的 Int 边界
internal sealed interface WriteResult {
    data class Full(val bytes: ByteArray) : WriteResult
    data class HeadAndTail(val head: ByteArray, val audioStart: Long) : WriteResult
    // 中间音频体按 [bodyStart, bodyEnd) 流式复制，尾部追加元数据（用于 WAV 文件尾 ID3 标签）
    data class HeadAndRange(val head: ByteArray, val bodyStart: Long, val bodyEnd: Long, val tail: ByteArray) : WriteResult
}

/**
 * 元数据重写方案：头部字面字节替换源文件 [0, bodyStart)，音频躯干按 [bodyStart, bodyEnd)
 * 逐块搬运，尾部字面字节追加在末尾。
 *
 * 解析只吃头部窗口，音频躯干不整体驻留内存，单次重写的峰值内存与文件大小解耦。
 * 在线缓存高解析无损（单文件可达数百 MB）时，整文件驻留会把进程推到系统内存回收线以下，
 * 被系统直接杀死且不产生任何崩溃日志。
 */
internal class RewritePlan(
    val head: ByteArray,
    val bodyStart: Long,
    val bodyEnd: Long,
    val tail: ByteArray,
) {
    // 落到字节数组（整文件解析路径与测试用）：躯干区间按实际可用长度截断
    fun materialize(body: ByteArray): ByteArray {
        val start = bodyStart.coerceIn(0L, body.size.toLong()).toInt()
        val end = bodyEnd.coerceIn(start.toLong(), body.size.toLong()).toInt()
        return head + body.copyOfRange(start, end) + tail
    }
}

// 容器嗅探窗口：各容器魔数都在文件最前，该窗口同时覆盖 OpusHead 的定位范围
internal const val SNIFF_WINDOW_BYTES = 64 * 1024

// 单次重写允许驻留内存的字节上限：标签解析窗口与按原字节搬运的 MP4 顶层盒子共用该上限，
// 超过即放弃本次重写并记日志，不无限扩大驻留量
internal const val MAX_IN_MEMORY_BYTES = 32 * 1024 * 1024

// 读取 [offset, offset + size) 的解析窗口；超过窗口上限或短读时返回 null
internal fun readParseWindow(source: TagSource, offset: Long, size: Long): ByteArray? {
    if (size > MAX_IN_MEMORY_BYTES) return null
    val window = source.readAt(offset, size.toInt()) ?: return null
    return window.takeIf { it.size.toLong() == size }
}

// 把整文件解析结果转为重写方案：音频体区间落在给定的字节源上，写出时按区间搬运
internal fun WriteResult.toPlan(source: TagSource): RewritePlan = when (this) {
    is WriteResult.Full -> RewritePlan(bytes, 0L, 0L, ByteArray(0))
    is WriteResult.HeadAndTail -> RewritePlan(head, audioStart, source.size, ByteArray(0))
    is WriteResult.HeadAndRange -> RewritePlan(head, bodyStart, bodyEnd, tail)
}
