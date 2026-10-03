package com.yichao.evilgodxu.ui.component

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
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
 * 首页大封面另走 [rememberLargeCoverState]（系统略缩图先出图，内嵌原图长边至 [LargeCoverStore.MAX_EDGE_PX] 后替换）；
 * 沉浸背景取色只要 64px 一档。
 *
 * 取图顺序：内存缓存 → 当前曲目的落盘封面（[CurrentCoverCache]，冷启动时跳过系统略缩图查询与内嵌封面解码）
 * → [MusicCoverLoader]（索引曲目读系统媒体库略缩图，非索引曲目解码音频文件的内嵌封面）；
 * 三处都取不到时即由调用方显示占位符。
 *
 * 重载时机由 [coverRevision] 驱动：封面重写后音频文件的 URI 不变而略缩图已变，
 * 仅靠 URI 作键会让已解码的旧图一直命中。
 *
 * 取图状态按「影响取图的全部输入」作键重建：初值只在状态创建时生效，
 * 不随键重建的话换曲后状态仍持有上一曲的位图，新图到位前显示端一直停在上一曲。
 */
@Composable
internal fun rememberSystemThumbnail(track: MusicTrack?, sizePx: Int): ImageBitmap? {
    val context = LocalContext.current
    val audioUri = track?.audioUri
    val coverRevision = LocalMusicPanelStateHolder.current.state.coverRevision
    // 初值同步取回，避免进出页面重建后先闪占位符再出图；
    // 当前曲目另有已落盘的封面（冷启动预读已驻留内存），一并同步取用，使冷启动首帧直接出图。
    // 键带 coverRevision：封面重写已作废缓存与落盘封面，避免把旧图作为初值顶出。
    val thumbnail = remember(audioUri, track?.id, sizePx, coverRevision) {
        val target = track
        mutableStateOf(
            runCatching {
                target?.let { memoryCover(it, sizePx) ?: CurrentCoverCache.peek(it.audioUri) }
            }.getOrNull()?.asImageBitmap()
        )
    }
    LaunchedEffect(audioUri, track?.id, sizePx, coverRevision) {
        val target = track
        thumbnail.value = if (target == null) {
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
    return thumbnail.value
}

/**
 * 首页大封面（竖屏沉浸封面、横屏融合封面）专用取图，分三步：
 *
 * 1. **快速占位**：先取随时能拿到的档位（内存驻留 → 落盘高清档 → 系统最大档略缩图，见 [LargeCoverStore.quick]）；
 * 2. **异步解码高清原图**：内嵌原图长边至 [LargeCoverStore.MAX_EDGE_PX]（见 [LargeCoverStore.get]），
 *    从列表直接点选任意曲目时这一步要读一次内嵌图并解码，可能要 1–3 秒，故不能让它挡住首帧；
 *    相邻曲目的预热顺序是 当前曲 → 下一曲 → 上一曲，见播放状态的邻近曲目预取；
 * 3. **平滑过渡**：两步之间的换图由调用方（HomeAlbumArt）做淡入淡出，不在这里直接替换画面。
 *
 * 预取已备好的相邻曲目在第 1 步就直接命中高清档，不会出现先降后升。
 * 不复用 [rememberSystemThumbnail]：它的系统略缩图上限 512，铺满首屏只能放大渲染而发虚。
 * 初值取内存驻留档或落盘缩略图，使冷启动首帧就有图。
 *
 * 取图状态按曲目作键重建，换曲即同步落到新曲目的初值：上一曲的图不会被沿用，
 * 第 1 步的占位档也就一定会为「初值为空」的新曲目执行 —— 否则换曲后画面停在上一曲，
 * 直到第 2 步的高清档到位才跳变，期间系统略缩图这一档被整段跳过。
 *
 * 返回值把「还没取到」与「确认取不到」分开（见 [LargeCoverState]）：显示端据此决定是沿用上一张封面
 * 还是退回占位符——后者是与页面底色同为近黑的色块，选曲播放的整屏揭示圆心正落在封面区，会随圆一起展开。
 */
@Composable
internal fun rememberLargeCoverState(track: MusicTrack?): LargeCoverState {
    val context = LocalContext.current
    val audioUri = track?.audioUri
    val coverRevision = LocalMusicPanelStateHolder.current.state.coverRevision
    val cover = remember(audioUri, track?.id, coverRevision) {
        val target = track
        mutableStateOf(
            runCatching {
                target?.let { LargeCoverStore.peek(it.audioUri) ?: CurrentCoverCache.peek(it.audioUri) }
            }.getOrNull()?.asImageBitmap()
        )
    }
    // 两级取图都跑完才算「已定论」：在此之前为空只说明还没取到，而不是这首没有封面
    val settled = remember(audioUri, track?.id, coverRevision) { mutableStateOf(cover.value != null) }
    LaunchedEffect(audioUri, track?.id, coverRevision) {
        val target = track
        if (target == null) {
            cover.value = null
            settled.value = true
            return@LaunchedEffect
        }
        // 一级：先出图。已有初值（内存高清档或落盘缩略图）时跳过，
        // 否则会把已经就位的高清档降级成略缩图再升回去
        if (cover.value == null) {
            withContext(Dispatchers.IO) {
                runCatching { LargeCoverStore.quick(context, target) }.getOrNull()
            }?.let { cover.value = it.asImageBitmap() }
        }
        // 二级：解码高清原图后无缝替换。无可用封面时保持一级结果或占位图，不把已出的图撤下
        withContext(Dispatchers.IO) {
            runCatching { LargeCoverStore.get(context, target) }.getOrNull()
        }?.let { cover.value = it.asImageBitmap() }
        settled.value = true
    }
    return LargeCoverState(cover = cover.value, pending = !settled.value)
}

/**
 * 大封面的取图状态。
 *
 * [cover] 为当前可显示的封面；[pending] 表示这张曲目的封面仍在取图中且眼下无图可显示。
 * 两者分开是为了让显示端在 [pending] 时先沿用上一张已就位的封面：占位符是与页面底色同为近黑的色块，
 * 换曲时直接露出来就是一次黑闪。确认取不到封面（[pending] 为假且 [cover] 为空）时才轮到占位符。
 */
@Immutable
internal data class LargeCoverState(
    val cover: ImageBitmap?,
    val pending: Boolean,
)

// 内存缓存中的封面：索引曲目取系统略缩图，非索引曲目取内嵌封面；未命中返回 null
private fun memoryCover(track: MusicTrack, sizePx: Int): Bitmap? =
    if (track.isMediaStoreIndexed) {
        SystemThumbnailCache.get(track.audioUri, sizePx)
    } else {
        EmbeddedCoverCache.peek(track.id, sizePx)
    }
