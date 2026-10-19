package com.yichao.evilgodxu.data.music.metadata

import android.net.Uri
import androidx.core.net.toUri
import com.yichao.evilgodxu.data.music.model.MusicTrack

// 系统封面来源：封面取自系统（MediaProvider 的专辑封面缓存），应用不为系统面板另存封面。
// 系统媒体面板与沉浸背景共用此入口，「是否为索引曲目」与「系统封面 URI 如何拼装」只在此定义一次。

private const val MEDIA_STORE_URI_PREFIX = "content://media/"

// 是否为 MediaStore 索引曲目：系统略缩图与系统封面仅对这类曲目可用
internal val MusicTrack.isMediaStoreIndexed: Boolean
    get() = audioUri.startsWith(MEDIA_STORE_URI_PREFIX)

/**
 * 系统媒体面板的封面 URI：本地索引曲目取 MediaProvider 的条目级专辑封面，
 * 非索引曲目（在线播放）回退在线封面地址。
 *
 * 面板封面必须在首次 setMediaItems 时就位：通知只在元数据变化时重建，事后替换 MediaItem 属
 * 时间线变更，面板不会重新取图，封面会缺席到用户切歌。在线地址交给 media3 的 BitmapLoader
 * 异步下载——未完成的 future 会被挂上回调，加载完成后自动重发通知。
 *
 * 索引曲目在音频条目 URI 上追加 albumart 段（AOSP LocalUriMatcher.AUDIO_ALBUMART_FILE_ID），
 * 列表略缩图读的是同一份系统封面；按曲目自身的 audioUri 追加，卷名与 URI 形态随曲目本来的形态。
 */
internal fun panelArtworkUri(track: MusicTrack?): Uri? {
    val target = track ?: return null
    if (target.isMediaStoreIndexed) {
        return target.audioUri.toUri()
            .buildUpon()
            .clearQuery()
            .fragment(null)
            .appendPath("albumart")
            .build()
    }
    return target.neteaseCoverUrl.takeIf { it.isNotBlank() }?.toUri()
}
