package com.yichao.evilgodxu.data.music.metadata

import android.content.Context
import com.yichao.evilgodxu.data.music.download.sanitizeFileName
import com.yichao.evilgodxu.data.music.model.MusicTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 内嵌封面导出：把曲目的内嵌封面原图写入系统相册，供用户在图库中查看、分享或另作他用。
// 原图直出、不缩放不重编码——这是用户显式发起的保存，产物是他的数据而非应用缓存，
// 故落在相册目录而非缓存目录，也不纳入「清理缓存」作用域（见 CacheInventory）。
// 相册写入协议由 AlbumImageStore 承担，与频谱图导出共用
internal object CoverExporter {

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
        if (AlbumImageStore.write(context, name, mime, bytes)) Result.Saved else Result.Failed
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
