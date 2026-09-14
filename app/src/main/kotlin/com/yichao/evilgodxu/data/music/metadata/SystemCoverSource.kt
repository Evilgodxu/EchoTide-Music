package com.yichao.evilgodxu.data.music.metadata

import android.net.Uri
import com.yichao.evilgodxu.data.music.model.MusicTrack

// 系统封面来源：封面取自系统（MediaProvider 的专辑封面缓存），应用不落盘封面。
// 系统媒体面板与沉浸背景共用此入口，「是否为索引曲目」与「系统封面 URI 如何拼装」只在此定义一次。

private const val MEDIA_STORE_URI_PREFIX = "content://media/"

// 是否为 MediaStore 索引曲目：系统略缩图与系统封面仅对这类曲目可用
internal val MusicTrack.isMediaStoreIndexed: Boolean
    get() = audioUri.startsWith(MEDIA_STORE_URI_PREFIX)

/**
 * 系统封面 URI：在音频条目 URI 上追加 albumart 段，取 MediaProvider 的条目级专辑封面。
 *
 * MediaProvider 以 `audio/media/#/albumart` 匹配该 URI（AOSP LocalUriMatcher.AUDIO_ALBUMART_FILE_ID），
 * 列表略缩图读的是同一份系统封面；按曲目自身的 audioUri 追加而非另拼 authority，
 * 卷名与 URI 形态随曲目本来的形态，同一事实不出现第二份。
 *
 * 非索引曲目没有系统封面，返回 null 交由调用方回退。
 */
internal fun systemCoverUri(track: MusicTrack?): Uri? {
    val target = track ?: return null
    if (!target.isMediaStoreIndexed) return null
    return Uri.parse(target.audioUri)
        .buildUpon()
        .clearQuery()
        .fragment(null)
        .appendPath("albumart")
        .build()
}
