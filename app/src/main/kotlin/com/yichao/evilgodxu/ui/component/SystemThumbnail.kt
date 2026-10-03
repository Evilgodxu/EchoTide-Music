package com.yichao.evilgodxu.ui.component

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.yichao.evilgodxu.LocalMusicPanelStateHolder
import com.yichao.evilgodxu.data.music.metadata.CurrentCoverCache
import com.yichao.evilgodxu.data.music.metadata.EmbeddedCoverCache
import com.yichao.evilgodxu.data.music.metadata.LargeCoverStore
import com.yichao.evilgodxu.data.music.metadata.MusicCoverLoader
import com.yichao.evilgodxu.data.music.metadata.SystemThumbnailCache
import com.yichao.evilgodxu.data.music.metadata.isMediaStoreIndexed
import com.yichao.evilgodxu.data.music.model.MusicTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 封面显示唯一入口：所有封面显示（列表行、音乐面板光碟、迷你播放器、3D 轮播、首页大封面、沉浸背景取色）都经此处取图。
 *
 * 请求尺寸按展示场景分级（见 ui/component 的封面请求尺寸策略）：
 * 列表行、光碟、刷新预览按控件实际渲染尺寸换算；3D 轮播固定 [CAROUSEL_COVER_THUMBNAIL_SIZE]；
 * 首页大封面另走 [rememberLargeCover]（内嵌原图，长边至 [LargeCoverStore.MAX_EDGE_PX]）；
 * 沉浸背景取色只要 64px 一档。
 *
 * 取图顺序：内存缓存 → 当前曲目的落盘封面（[CurrentCoverCache]，冷启动时跳过系统略缩图查询与内嵌封面解码）
 * → [MusicCoverLoader]（索引曲目读系统媒体库略缩图，非索引曲目解码音频文件的内嵌封面）；
 * 三处都取不到时即由调用方显示占位符。
 *
 * 重载时机由 [coverRevision] 驱动：封面重写后音频文件的 URI 不变而略缩图已变，
 * 仅靠 URI 作键会让已解码的旧图一直命中。
 */
@Composable
internal fun rememberSystemThumbnail(track: MusicTrack?, sizePx: Int): ImageBitmap? {
    val context = LocalContext.current
    val audioUri = track?.audioUri
    val coverRevision = LocalMusicPanelStateHolder.current.state.coverRevision
    // 内存缓存命中时同步取回作为初始值，避免进出页面重建后先闪占位符再出图；
    // 当前曲目另有已落盘的封面（冷启动预读已驻留内存），一并同步取用，使冷启动首帧直接出图。
    // 键带 coverRevision：封面重写已作废缓存与落盘封面，避免把旧图作为初始值顶出。
    val cachedThumbnail = remember(audioUri, track?.id, sizePx, coverRevision) {
        val target = track ?: return@remember null
        runCatching {
            memoryCover(target, sizePx) ?: CurrentCoverCache.peek(target.audioUri)
        }.getOrNull()?.asImageBitmap()
    }
    val thumbnail by produceState<ImageBitmap?>(
        initialValue = cachedThumbnail,
        audioUri,
        coverRevision,
    ) {
        val target = track
        value = if (target == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    memoryCover(target, sizePx)
                        // 当前曲目的落盘封面优先于重新解码：冷启动沿用上次切歌时保存的同一张图
                        ?: CurrentCoverCache.load(context, target.audioUri)
                        ?: MusicCoverLoader.load(context, target, sizePx)
                }.getOrNull()?.asImageBitmap()
            }
        }
    }
    return thumbnail
}

/**
 * 首页大封面（竖屏沉浸封面、横屏融合封面）专用取图：直接解码音频内嵌原图并按长边收口。
 *
 * 不复用 [rememberSystemThumbnail]：系统略缩图上限 512，铺满首屏只能放大渲染而发虚。
 * 无内嵌封面或解码失败时由 [MusicCoverLoader.loadLarge] 回退系统略缩图，与其余封面口径一致。
 *
 * 初始值依次取：大封面内存驻留档 → 落盘缩略图，使冷启动首帧就有图，
 * 大封面异步就位后无缝替换，期间不出现占位符。
 */
@Composable
internal fun rememberLargeCover(track: MusicTrack?): ImageBitmap? {
    val context = LocalContext.current
    val audioUri = track?.audioUri
    val coverRevision = LocalMusicPanelStateHolder.current.state.coverRevision
    val cachedCover = remember(audioUri, track?.id, coverRevision) {
        val target = track ?: return@remember null
        runCatching {
            LargeCoverStore.peek(target.audioUri) ?: CurrentCoverCache.peek(target.audioUri)
        }.getOrNull()?.asImageBitmap()
    }
    val cover by produceState<ImageBitmap?>(
        initialValue = cachedCover,
        audioUri,
        coverRevision,
    ) {
        val target = track
        value = if (target == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    LargeCoverStore.get(context, target)
                }.getOrNull()?.asImageBitmap()
            }
        }
    }
    return cover
}

// 内存缓存中的封面：索引曲目取系统略缩图，非索引曲目取内嵌封面；未命中返回 null
private fun memoryCover(track: MusicTrack, sizePx: Int): Bitmap? =
    if (track.isMediaStoreIndexed) {
        SystemThumbnailCache.get(track.audioUri, sizePx)
    } else {
        EmbeddedCoverCache.peek(track.id, sizePx)
    }
