package com.yichao.evilgodxu.data.music.metadata

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import com.yichao.evilgodxu.log.CrashLogManager

// 系统相册条目的图片写入原语，供封面导出与频谱图导出共用。
// 先以 pending 插入、写给完成后再转正，避免相册读到半截文件；
// 写入失败即删除条目，避免相册残留 0 字节空条目（异常中断的 pending 条目由系统按超期自动回收）。
// 应用自身插入的图片无需任何存储权限；同名文件由系统自动改名，不覆盖用户既有图片
internal object AlbumImageStore {

    // 相册内的应用目录名：与公共下载目录下的缓存目录同名，便于用户把两者对上
    const val ALBUM_DIR_NAME = "YiChaoMusic"

    /** 把图片字节写入相册。阻塞 IO，调用方须在 IO 线程调用；返回是否写入成功 */
    fun write(context: Context, name: String, mime: String, bytes: ByteArray): Boolean {
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
            ) ?: return false
            val written = resolver.openOutputStream(uri, "w")?.use { it.write(bytes) } != null
            if (!written) {
                runCatching { resolver.delete(uri, null, null) }
                return false
            }
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                null,
                null,
            )
            true
        } catch (e: Exception) {
            CrashLogManager.logException("AlbumImageStore", "图片写入相册失败: $name", e)
            false
        }
    }
}
