package com.yichao.evilgodxu.screens.home.component.queue

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.ui.component.COVER_PREFETCH_RADIUS
import com.yichao.evilgodxu.ui.component.listCoverThumbnailSize
import com.yichao.evilgodxu.ui.component.prefetchCoverBatch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * 队列封面预加载：进入首页即由当前曲目向外辐射取图（本组件随面板一同常驻组合树，列表布局与否都跑），
 * 把上下各 [COVER_PREFETCH_RADIUS] 项的列表缩略图提前解码进内存缓存。
 *
 * 窗口锚定当前曲目而非可视区，补的是「面板尚未展开、列表还没有视口」这段空档；
 * 展开并开始滚动后由列表自身的视口窗口接手（见 CoverPrefetch），两者共用同一规模与同一份缓存，
 * 于是展开时首帧即有封面，其余显示这些曲目的列表也一并受益。
 *
 * 取图档位与列表行一致（见 [listCoverThumbnailSize]）：档位不一致时预取结果无法被行命中，
 * 既白取一次图，又占着系统略缩图查询。
 *
 * [currentTrackId] 不在 [tracks] 内（浏览的歌单不含当前曲目）时窗口无从定心，交由各行按需取图。
 */
@Composable
internal fun QueueCoverPreload(
    tracks: List<MusicTrack>,
    currentTrackId: Long?,
) {
    val context = LocalContext.current
    val sizePx = listCoverThumbnailSize(LocalDensity.current)
    LaunchedEffect(tracks, currentTrackId, sizePx) {
        val center = tracks.indexOfFirst { it.id == currentTrackId }
        if (center < 0) return@LaunchedEffect
        // 中心变化（切歌、换歌单、重排）时不撤在飞的取图：同一张图常同时被可视行在取，
        // 撤掉会让正等着它的行停在占位图上；作废的批次至多白扫一遍内存缓存，代价远低于让行卡住
        withContext(NonCancellable) {
            prefetchCoverBatch(
                context = context,
                sizePx = sizePx,
                order = preloadOrder(center, tracks.size),
                trackAt = { index -> tracks.getOrNull(index) },
            )
        }
    }
}

// 取图顺序：当前曲目居中，按离中心的距离由近及远；同距时先取下方 ——
// 播放往下走，先被看到的是当前曲目之后的几首
private fun preloadOrder(center: Int, count: Int): List<Int> {
    val order = ArrayList<Int>(minOf(count, COVER_PREFETCH_RADIUS * 2 + 1))
    order += center
    for (offset in 1..COVER_PREFETCH_RADIUS) {
        val down = center + offset
        val up = center - offset
        if (down < count) order += down
        if (up >= 0) order += up
    }
    return order
}
