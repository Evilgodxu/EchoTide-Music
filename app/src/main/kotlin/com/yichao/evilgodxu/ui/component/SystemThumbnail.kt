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
import com.yichao.evilgodxu.data.music.metadata.MusicCoverLoader
import com.yichao.evilgodxu.data.music.metadata.SystemThumbnailCache
import com.yichao.evilgodxu.data.music.metadata.isMediaStoreIndexed
import com.yichao.evilgodxu.data.music.model.MusicTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 封面显示唯一入口：所有封面显示（列表行、音乐面板、迷你播放器、轮播、首页大封面、沉浸背景取色）都经此处取图。
 *
 * 取图顺序：内存缓存 → 当前曲目的落盘封面（[CurrentCoverCache]，冷启动时跳过系统略缩图查询与内嵌封面解码）
 * → [MusicCoverLoader]（索引曲目读系统媒体库略缩图，非索引曲目解码音频文件的内嵌封面）；
 * 三处都取不到时即由调用方显示占位符。
 * [sizePx] 按显示尺寸适配：列表行 256，音乐面板/轮播/首页大封面 512，沉浸背景取色 64。
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

// 内存缓存中的封面：索引曲目取系统略缩图，非索引曲目取内嵌封面；未命中返回 null
private fun memoryCover(track: MusicTrack, sizePx: Int): Bitmap? =
    if (track.isMediaStoreIndexed) {
        SystemThumbnailCache.get(track.audioUri, sizePx)
    } else {
        EmbeddedCoverCache.peek(track.id, sizePx)
    }