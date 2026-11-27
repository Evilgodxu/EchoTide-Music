package com.yichao.evilgodxu.data.music.metadata

import android.net.Uri
import androidx.core.net.toUri
import com.yichao.evilgodxu.data.music.model.MusicTrack

// 系统封面来源，系统面板与沉浸背景共用

private const val MEDIA_STORE_URI_PREFIX = "content://media/"

// 是否为 MediaStore 索引曲目
internal val MusicTrack.isMediaStoreIndexed: Boolean
    get() = audioUri.startsWith(MEDIA_STORE_URI_PREFIX)

/** 系统面板封面：索引曲目取 albumart，否则回退在线地址 */
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
