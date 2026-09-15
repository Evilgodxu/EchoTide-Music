package com.yichao.evilgodxu.data.music.metadata

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import com.yichao.evilgodxu.data.music.model.MusicTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 封面取图统一入口：内存缓存命中即复用，未命中才查系统略缩图或解码音频内嵌封面，并把结果回填内存缓存。
 *
 * 显示端（rememberSystemThumbnail）与切歌时的后台落盘共用本入口，使两路取图口径一致且不重复解码。
 * 本入口自身不产生落盘产出，当前曲目封面缩略图的落盘由 [CurrentCoverCache] 负责。
 */
internal object MusicCoverLoader {

    /** 取曲目封面（按最长边 [sizePx] 请求解码）；曲目无可用封面或读取失败时返回 null */
    suspend fun load(context: Context, track: MusicTrack, sizePx: Int): Bitmap? = withContext(Dispatchers.IO) {
        if (track.isMediaStoreIndexed) {
            // 索引曲目取系统媒体库维护的略缩图（扫描时生成、音频内嵌封面被重写后随媒体扫描重建）
            SystemThumbnailCache.get(track.audioUri, sizePx) ?: runCatching {
                context.contentResolver.loadThumbnail(Uri.parse(track.audioUri), Size(sizePx, sizePx), null)
            }.getOrNull()?.also { SystemThumbnailCache.put(track.audioUri, sizePx, it) }
        } else {
            // 非索引曲目不在媒体库中，没有系统略缩图可取，改读音频文件自身的内嵌封面；
            // 读取与解码的去重、往返复用由缓存持有，调用方只负责按需请求
            EmbeddedCoverCache.cover(context, track, sizePx)
        }
    }
}