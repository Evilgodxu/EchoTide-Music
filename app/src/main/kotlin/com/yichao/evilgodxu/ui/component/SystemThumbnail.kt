package com.yichao.evilgodxu.ui.component

import android.net.Uri
import android.util.Size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.yichao.evilgodxu.LocalMusicPanelStateHolder
import com.yichao.evilgodxu.data.music.metadata.EmbeddedCoverCache
import com.yichao.evilgodxu.data.music.metadata.SystemThumbnailCache
import com.yichao.evilgodxu.data.music.metadata.isMediaStoreIndexed
import com.yichao.evilgodxu.data.music.model.MusicTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 系统略缩图：应用内所有封面显示的入口。
 *
 * 索引曲目取系统媒体库维护的略缩图（扫描时生成、音频内嵌封面被重写后随媒体扫描重建）；
 * 非索引曲目（外部分享/导入的曲目）不在媒体库中，没有系统略缩图可取，改读音频文件自身的内嵌封面，
 * 解码行为与系统生成音频略缩图的路径保持一致，结果只驻留内存、不落盘。
 * 两条路由都取不到时即由调用方显示占位符。
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
    // 未命中时与往常一致先占位，待 produceState 在 IO 上解码回填。
    // 键带 coverRevision：封面重写已清空缓存，避免把旧略缩图作为初始值顶出。
    val cachedThumbnail = remember(audioUri, track?.id, sizePx, coverRevision) {
        val target = track ?: return@remember null
        runCatching {
            if (target.isMediaStoreIndexed) {
                SystemThumbnailCache.get(target.audioUri, sizePx)?.asImageBitmap()
            } else {
                EmbeddedCoverCache.peek(target.id, sizePx)?.asImageBitmap()
            }
        }.getOrNull()
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
                if (target.isMediaStoreIndexed) {
                    // 命中内存缓存直接复用；未命中才走媒体库查询与解码，并把结果回填缓存，
                    // 使滚出再滚入视口的行不再重复执行昂贵的略缩图读取
                    runCatching {
                        SystemThumbnailCache.get(target.audioUri, sizePx)
                            ?: context.contentResolver.loadThumbnail(
                                Uri.parse(target.audioUri),
                                Size(sizePx, sizePx),
                                null,
                            ).also { loaded ->
                                SystemThumbnailCache.put(target.audioUri, sizePx, loaded)
                            }
                    }.getOrNull()?.asImageBitmap()
                } else {
                    // 读取与解码的去重、往返复用由缓存持有，组件只负责按需请求
                    EmbeddedCoverCache.cover(context, target, sizePx)?.asImageBitmap()
                }
            }
        }
    }
    return thumbnail
}
