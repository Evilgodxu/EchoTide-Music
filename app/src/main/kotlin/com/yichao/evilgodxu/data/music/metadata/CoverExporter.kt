package com.yichao.evilgodxu.data.music.metadata

import android.content.Context
import com.yichao.evilgodxu.data.music.download.sanitizeFileName
import com.yichao.evilgodxu.data.music.model.MusicTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 内嵌封面导出到相册：原图直出，不缩放不重编码
internal object CoverExporter {

    enum class Result {
        /** 已写入相册 */
        Saved,
        /** 无内嵌封面可导出 */
        NoEmbeddedCover,
        /** 写入相册失败 */
        Failed,
    }

    suspend fun export(context: Context, track: MusicTrack): Result = withContext(Dispatchers.IO) {
        val bytes = EmbeddedCoverReader.readRawBytes(context, track.audioUri, track.path)
            ?: return@withContext Result.NoEmbeddedCover
        writeToAlbum(context, track, bytes)
    }

    /** 存入指定封面字节，供编辑页未落盘时使用 */
    suspend fun exportBytes(context: Context, track: MusicTrack, bytes: ByteArray): Result =
        withContext(Dispatchers.IO) {
            if (bytes.isEmpty()) return@withContext Result.NoEmbeddedCover
            writeToAlbum(context, track, bytes)
        }

    // 按真实类型定名写入相册
    private fun writeToAlbum(context: Context, track: MusicTrack, bytes: ByteArray): Result {
        val mime = MusicMetadataWriter.sniffMimeType(bytes)
        val name = "${coverFileName(track)}.${extensionOf(mime)}"
        return if (AlbumImageStore.write(context, name, mime, bytes)) Result.Saved else Result.Failed
    }

    // 文件名“标题 - 艺术家”，全空回退 cover_id
    private fun coverFileName(track: MusicTrack): String = sanitizeFileName(
        listOf(track.title, track.artist).filter { it.isNotBlank() }.joinToString(" - ")
    ).ifBlank { "cover_${track.id}" }

    // 扩展名取自真实类型
    private fun extensionOf(mime: String): String = when (mime) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        else -> "jpg"
    }
}
