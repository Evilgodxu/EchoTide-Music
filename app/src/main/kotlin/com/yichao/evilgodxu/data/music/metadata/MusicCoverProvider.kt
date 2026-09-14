package com.yichao.evilgodxu.data.music.metadata

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.yichao.evilgodxu.log.CrashLogManager
import java.io.File
import java.io.FileNotFoundException

/**
 * 向系统媒体控制器（通知栏、锁屏、Android Auto 等）暴露本地缓存的封面文件。
 *
 * Media3 的 MediaSession 会自动为 content:// URI 授予读取权限，
 * 因此系统进程能通过此 Provider 读取本地缓存目录下的封面文件。
 *
 * URI 格式: content://{packageName}.musiccover/{文件名不含扩展名}
 * 例如: content://com.yichao.evilgodxu.musiccover/晴天 - 周杰伦
 *
 * 按 webp → png → image(旧版) 顺序查找文件。
 */
class MusicCoverProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val id = uri.lastPathSegment ?: return null
        val ctx = context ?: return null
        val root = MusicMetadataCache.coverRoot(ctx)
        val file = File(root, "$id.webp").takeIf { it.isFile }
            ?: File(root, "$id.png").takeIf { it.isFile }
            ?: File(root, "$id.image").takeIf { it.isFile }
            ?: return null
        return try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: FileNotFoundException) {
            CrashLogManager.logException("MusicCoverProvider", "封面文件不存在: ${file.absolutePath}", e)
            null
        }
    }

    override fun getType(uri: Uri): String? = "image/*"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null

    companion object {
        const val AUTHORITY_SUFFIX = ".musiccover"

        /**
         * 根据 coverCachePath 生成 content:// URI。
         *
         * 缓存文件名取自「标题 - 艺术家」索引，可能含空格、`#`、`%` 等对 URI 有语义的字符，
         * 故逐段经 Builder 追加（自动转义），不按字符串拼接——拼接会让 `#` 之后的部分被当作
         * fragment 丢弃，解析回来的路径段与 openFile 要查的文件名不一致
         */
        fun buildUri(packageName: String, coverCachePath: String): Uri? {
            if (coverCachePath.isBlank()) return null
            val name = File(coverCachePath).nameWithoutExtension
            return Uri.Builder()
                .scheme("content")
                .authority("$packageName$AUTHORITY_SUFFIX")
                .appendPath(name)
                .build()
        }
    }
}
