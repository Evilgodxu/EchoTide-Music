package com.yichao.evilgodxu.ui.component

import android.net.Uri
import android.util.Size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.yichao.evilgodxu.LocalMusicPanelStateHolder
import com.yichao.evilgodxu.data.music.metadata.isMediaStoreIndexed
import com.yichao.evilgodxu.data.music.model.MusicTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 系统略缩图：应用内所有封面显示的唯一来源。
 *
 * 略缩图由系统媒体库维护（扫描时生成、音频内嵌封面被重写后随媒体扫描重建），
 * 应用不再自建封面缓存，也不以任何其它来源兜底——取不到即由调用方显示占位符。
 * [sizePx] 按显示尺寸适配：列表行 256，音乐面板/轮播/首页大封面 512，沉浸背景取色 64。
 *
 * 重载时机由 [coverRevision] 驱动：封面重写后音频文件的 URI 不变而系统略缩图已变，
 * 仅靠 URI 作键会让已解码的旧图一直命中。
 */
@Composable
internal fun rememberSystemThumbnail(track: MusicTrack?, sizePx: Int): ImageBitmap? {
    val context = LocalContext.current
    val audioUri = track?.audioUri
    val indexed = track?.isMediaStoreIndexed == true
    val coverRevision = LocalMusicPanelStateHolder.current.state.coverRevision
    val thumbnail by produceState<ImageBitmap?>(
        initialValue = null,
        audioUri,
        coverRevision,
    ) {
        value = if (audioUri == null || !indexed) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.loadThumbnail(
                        Uri.parse(audioUri),
                        Size(sizePx, sizePx),
                        null,
                    ).asImageBitmap()
                }.getOrNull()
            }
        }
    }
    return thumbnail
}
