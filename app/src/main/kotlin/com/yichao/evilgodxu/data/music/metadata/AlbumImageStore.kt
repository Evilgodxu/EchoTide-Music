package com.yichao.evilgodxu.data.music.metadata

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.yichao.evilgodxu.log.CrashLogManager

// 相册图：pending 插入后转正
internal object AlbumImageStore {

    // 相册内应用目录名
    const val ALBUM_DIR_NAME = "EchoTideMusic"

    // 阻塞 IO，调用方须在 IO 线程调用
    fun write(context: Context, name: String, mime: String, bytes: ByteArray): Boolean =
        writeToAlbum(context, name, mime, bytes) != null

    /** 写入相册并返回条目 URI；失败为 null */
    fun writeToAlbum(context: Context, name: String, mime: String, bytes: ByteArray): Uri? {
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        return try {
            val uri = resolver.insert(
                collection,
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, mime)
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM_DIR_NAME/")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                },
            ) ?: return null
            val written = resolver.openOutputStream(uri, "w")?.use { it.write(bytes) } != null
            if (!written) {
                runCatching { resolver.delete(uri, null, null) }
                return null
            }
            // 转正失败视为未保存，删除条目
            val published = resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                null,
                null,
            ) > 0
            if (!published) {
                runCatching { resolver.delete(uri, null, null) }
                return null
            }
            uri
        } catch (e: Exception) {
            CrashLogManager.logException("AlbumImageStore", "图片写入相册失败: $name", e)
            null
        }
    }
}
