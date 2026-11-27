package com.yichao.evilgodxu.data.music.metadata

import android.content.Context
import androidx.core.net.toUri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

// 读字节：路径优先，其次 URI
internal object LocalAudioSource {

    fun open(context: Context, path: String, audioUri: String): InputStream? = when {
        path.isNotBlank() -> runCatching { FileInputStream(path) }.getOrNull()
        audioUri.startsWith("content:") || audioUri.startsWith("file:") ->
            runCatching { context.contentResolver.openInputStream(audioUri.toUri()) }.getOrNull()
        else -> null
    }

    // 文件字节数；读不到返回 null
    fun size(context: Context, path: String, audioUri: String): Long? {
        val size = if (path.isNotBlank()) {
            runCatching { File(path).length() }.getOrNull()
        } else if (audioUri.startsWith("content:") || audioUri.startsWith("file:")) {
            runCatching {
                context.contentResolver.openFileDescriptor(audioUri.toUri(), "r")
                    ?.use { descriptor -> descriptor.statSize }
            }.getOrNull()
        } else {
            null
        }
        return size?.takeIf { it > 0 }
    }

    // 读区间，不足返回已读部分；越界或流不可用返回 null
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

    // 尾窗字节与其绝对偏移，长度未知为 null
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

    // skip 允许少跳，循环补齐
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
