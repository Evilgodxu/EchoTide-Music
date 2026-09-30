package com.yichao.evilgodxu.data.music.metadata

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * 标签读写的字节来源：只暴露区间读取与区间搬运，解析与写出都不必持有整文件。
 *
 * 高解析无损单文件可达数百 MB，整文件驻留会把进程推到系统内存回收线以下
 * （被系统直接杀死且不产生任何崩溃日志），故标签定位只读它所在的窗口，
 * 音频躯干一律按区间流式搬运。
 */
internal interface TagSource {

    /** 源长度；无法取得时返回 [UNKNOWN_SIZE]，区间搬运以 EOF 为准 */
    val size: Long

    /** 读取 [offset, offset + length) 区间；区间不可读返回 null，短读按实际读到的字节返回 */
    fun readAt(offset: Long, length: Int): ByteArray?

    /** 把 [start, end) 区间逐块搬运到 [out]，不整体驻留内存 */
    fun copyRange(start: Long, end: Long, out: OutputStream)

    /** 长度不可知：以文件末尾为界的定位（块表、尾部标签）在此值下无从成立 */
    companion object {
        const val UNKNOWN_SIZE = Long.MAX_VALUE
    }
}

// 本地文件源：每次读取都按请求长度定位后读，不缓存文件内容
internal class FileTagSource(private val file: File) : TagSource {
    override val size: Long = file.length()

    override fun readAt(offset: Long, length: Int): ByteArray? = runCatching {
        FileInputStream(file).use { input ->
            if (!skipFully(input, offset)) return@use null
            readUpTo(input, length)
        }
    }.getOrNull()

    override fun copyRange(start: Long, end: Long, out: OutputStream) {
        FileInputStream(file).use { input ->
            check(skipFully(input, start)) { "音频躯干定位失败: offset=$start" }
            copyUpTo(input, end - start, out)
        }
    }
}

// 在线缓存源（content URI）：定位读取同样按 ContentResolver 打开流后跳过，
// 长度取文件描述符上报值，读不到时按不可知处理
internal class ContentUriTagSource(private val context: Context, private val uri: Uri) : TagSource {
    override val size: Long = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
    }.getOrNull()?.takeIf { it > 0 } ?: TagSource.UNKNOWN_SIZE

    override fun readAt(offset: Long, length: Int): ByteArray? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            if (!skipFully(input, offset)) return@use null
            readUpTo(input, length)
        }
    }.getOrNull()

    override fun copyRange(start: Long, end: Long, out: OutputStream) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            check(skipFully(input, start)) { "音频躯干定位失败: offset=$start" }
            copyUpTo(input, end - start, out)
        } ?: throw IllegalStateException("无法打开音频输入流: uri=$uri")
    }
}

// 已驻留内存的字节源：字节入口与测试用
internal class ByteArrayTagSource(private val bytes: ByteArray) : TagSource {
    override val size: Long = bytes.size.toLong()

    override fun readAt(offset: Long, length: Int): ByteArray? {
        if (offset !in 0L..size) return null
        val end = minOf(size, offset + length).toInt()
        return bytes.copyOfRange(offset.toInt(), end)
    }

    override fun copyRange(start: Long, end: Long, out: OutputStream) {
        val from = start.coerceIn(0L, size).toInt()
        val to = end.coerceIn(from.toLong(), size).toInt()
        if (to > from) out.write(bytes, from, to - from)
    }
}

// 区间搬运与跳过的读缓冲大小
private const val STREAM_BUFFER_SIZE = 64 * 1024

// 跳过指定字节数：InputStream.skip 允许少跳，而 content URI 的流还可能直接返回 0
// （不可定位的流），故在 skip 无进展时退化为读取丢弃；未跳到位返回 false
internal fun skipFully(input: InputStream, count: Long): Boolean {
    var remaining = count
    val buffer = ByteArray(STREAM_BUFFER_SIZE)
    while (remaining > 0) {
        val step = input.skip(remaining)
        if (step > 0) {
            remaining -= step
            continue
        }
        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
        if (read <= 0) return false
        remaining -= read
    }
    return true
}

// 读取至多 size 字节，短读按实际读到的字节返回
internal fun readUpTo(input: InputStream, size: Int): ByteArray {
    val buffer = ByteArray(size)
    var read = 0
    while (read < size) {
        val step = input.read(buffer, read, size - read)
        if (step <= 0) break
        read += step
    }
    return buffer.copyOf(read)
}

// 按块搬运至多 length 字节（length 为 Long.MAX_VALUE 时搬到 EOF），不整体驻留内存
internal fun copyUpTo(input: InputStream, length: Long, output: OutputStream) {
    val buffer = ByteArray(STREAM_BUFFER_SIZE)
    var remaining = length
    while (remaining > 0) {
        val step = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
        if (step <= 0) return
        output.write(buffer, 0, step)
        remaining -= step
    }
}
