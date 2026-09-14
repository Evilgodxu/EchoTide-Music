package com.yichao.evilgodxu.data.music.metadata

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import com.yichao.evilgodxu.data.music.download.sanitizeFileName
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.log.CrashLogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 内嵌封面导出：把曲目的内嵌封面原图写入系统相册，供用户在图库中查看、分享或另作他用。
// 原图直出、不缩放不重编码——这是用户显式发起的保存，产物是他的数据而非应用缓存，
// 故落在相册目录而非缓存目录，也不纳入「清理缓存」作用域（见 CacheInventory）。
internal object CoverExporter {

    // 相册内的应用目录名：与公共下载目录下的缓存目录同名，便于用户把两者对上
    private const val ALBUM_DIR_NAME = "YiChaoMusic"

    enum class Result {
        /** 已写入相册 */
        Saved,
        /** 曲目没有内嵌封面可导出（如纯在线流，尚未落盘为本地文件） */
        NoEmbeddedCover,
        /** 写入相册失败 */
        Failed,
    }

    /** 将 [track] 的内嵌封面原图存入系统相册 */
    suspend fun export(context: Context, track: MusicTrack): Result = withContext(Dispatchers.IO) {
        val bytes = EmbeddedCoverReader.readRawBytes(context, track.audioUri, track.path)
            ?: return@withContext Result.NoEmbeddedCover
        val mime = MusicMetadataWriter.sniffMimeType(bytes)
        val name = "${coverFileName(track)}.${extensionOf(mime)}"
        if (writeToAlbum(context, name, mime, bytes)) Result.Saved else Result.Failed
    }

    // 相册条目写入：先以 pending 插入、写给完成后再转正，避免相册读到半截文件；
    // 写入失败即删除条目，避免相册残留 0 字节空条目（异常中断的 pending 条目由系统按超期自动回收）。
    // 应用自身插入的图片无需任何存储权限；同名文件由系统自动改名，不覆盖用户既有图片
    private fun writeToAlbum(context: Context, name: String, mime: String, bytes: ByteArray): Boolean {
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
            CrashLogManager.logException("CoverExporter", "封面写入相册失败: $name", e)
            false
        }
    }

    // 文件名与歌词/封面缓存的索引同规则：「标题 - 艺术家」，缺项自动省略；
    // 两者皆空时回退曲目 id，保证同名碰撞前至少可区分
    private fun coverFileName(track: MusicTrack): String = sanitizeFileName(
        listOf(track.title, track.artist).filter { it.isNotBlank() }.joinToString(" - ")
    ).ifBlank { "cover_${track.id}" }

    // 扩展名取自嗅探出的真实类型，使相册按正确的格式解析
    private fun extensionOf(mime: String): String = when (mime) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        else -> "jpg"
    }
}
