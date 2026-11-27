package com.yichao.evilgodxu.data.music.metadata

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream

/** 标签读写字节源：只暴露区间读取与搬运，不持有整文件 */
internal interface TagSource {

    /** 源长度；不可知为 UNKNOWN_SIZE，搬运以 EOF 为准 */
    val size: Long

    /** 读区间；不可读返回 null，短读按实际返回 */
    fun readAt(offset: Long, length: Int): ByteArray?

    /** 搬运 [start, end)；长度已知时短读视为失败 */
    fun copyRange(start: Long, end: Long, out: OutputStream)

    /** 长度不可知，按 EOF 为界 */
    companion object {
        const val UNKNOWN_SIZE = Long.MAX_VALUE
    }
}

// 本地文件源：按请求长度定位读取
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
            requireRangeComplete(start, end, copyUpTo(input, end - start, out))
        }
    }
}

// content URI：流式跳过定位，长度取 fd
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
            requireRangeComplete(start, end, copyUpTo(input, end - start, out))
        } ?: throw IllegalStateException("无法打开音频输入流: uri=$uri")
    }
}

// 内存字节源，供测试用
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
        requireRangeComplete(start, end, (to - from).toLong())
    }
}

private const val STREAM_BUFFER_SIZE = 64 * 1024

// 跳字节；skip 可能少跳或返回 0，退化为读取丢弃
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

// 按块搬运至多 length，返回实际字节数
internal fun copyUpTo(input: InputStream, length: Long, output: OutputStream): Long {
    val buffer = ByteArray(STREAM_BUFFER_SIZE)
    var remaining = length
    var copied = 0L
    while (remaining > 0) {
        val step = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
        if (step <= 0) return copied
        output.write(buffer, 0, step)
        copied += step
        remaining -= step
    }
    return copied
}

// 长度已知时区间必须完整搬运
private fun requireRangeComplete(start: Long, end: Long, copied: Long) {
    if (end == TagSource.UNKNOWN_SIZE) return
    check(copied == end - start) {
        "音频躯干复制不完整: offset=$start, 期望=${end - start}, 实际=$copied"
    }
}
