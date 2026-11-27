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
 * 以当前曲目为中心预加载前后封面
 * 档位须与列表一致，当前曲目不在列表则跳过
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
        // 不撤在飞取图，避免共用同图的行卡在占位图
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

// 取图顺序：由中心向两侧，同距先取下方（播放向下）
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
