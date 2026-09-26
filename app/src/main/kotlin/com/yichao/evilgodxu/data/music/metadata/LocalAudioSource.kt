package com.yichao.evilgodxu.data.music.metadata

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

// 本地音频源的字节读取：文件路径优先，其次 content/file URI；纯在线流取不到流。
// 内嵌歌词与内嵌封面读取共用同一套打开、长度查询与区间读取，
// 使「头部窗口 + 尾部窗口 + 窗口外定点读取」这套定位方式只有一处实现
internal object LocalAudioSource {

    fun open(context: Context, path: String, audioUri: String): InputStream? = when {
        path.isNotBlank() -> runCatching { FileInputStream(path) }.getOrNull()
        audioUri.startsWith("content:") || audioUri.startsWith("file:") ->
            runCatching { context.contentResolver.openInputStream(Uri.parse(audioUri)) }.getOrNull()
        else -> null
    }

    // 文件字节数。读不到时返回 null——不做「读整文件」的兜底，避免大文件全量读入
    fun size(context: Context, path: String, audioUri: String): Long? {
        val size = if (path.isNotBlank()) {
            runCatching { File(path).length() }.getOrNull()
        } else if (audioUri.startsWith("content:") || audioUri.startsWith("file:")) {
            runCatching {
                context.contentResolver.openFileDescriptor(Uri.parse(audioUri), "r")
                    ?.use { descriptor -> descriptor.statSize }
            }.getOrNull()
        } else {
            null
        }
        return size?.takeIf { it > 0 }
    }

    // 读取 [offset, offset + count) 区间的字节，不足时返回已读部分；区间越界或流不可用返回 null
    fun read(context: Context, path: String, audioUri: String, offset: Long, count: Int): ByteArray? {
        if (offset < 0 || count <= 0) return null
        val input = open(context, path, audioUri) ?: return null
        return runCatching {
            input.use { stream ->
                if (!skipFully(stream, offset)) return@use null
                readUpTo(stream, count)
            }
        }.getOrNull()
    }

    // 尾部窗口：自文件末回溯至多 cap 字节，返回窗口字节与其在文件中的绝对偏移。
    // 标签位于文件末尾的容器靠它定位；文件长度不可得时返回 null
    fun tail(context: Context, path: String, audioUri: String, cap: Int): Pair<ByteArray, Long>? {
        val size = size(context, path, audioUri) ?: return null
        val offset = (size - cap).coerceAtLeast(0)
        val bytes = read(context, path, audioUri, offset, (size - offset).toInt()) ?: return null
        return bytes to offset
    }

    fun readUpTo(stream: InputStream, count: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var remaining = count
        while (remaining > 0) {
            val read = stream.read(buffer, 0, minOf(buffer.size, remaining))
            if (read < 0) break
            out.write(buffer, 0, read)
            remaining -= read
        }
        return out.toByteArray()
    }

    // InputStream.skip 允许少跳，循环补齐
    private fun skipFully(stream: InputStream, count: Long): Boolean {
        var skipped = 0L
        while (skipped < count) {
            val step = stream.skip(count - skipped)
            if (step <= 0) return false
            skipped += step
        }
        return true
    }
}
