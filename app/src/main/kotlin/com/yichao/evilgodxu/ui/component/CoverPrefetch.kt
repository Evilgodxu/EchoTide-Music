package com.yichao.evilgodxu.ui.component

import android.content.Context
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import com.yichao.evilgodxu.data.music.metadata.MusicCoverLoader
import com.yichao.evilgodxu.data.music.model.MusicTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// 预取窗口半径 500，整窗约千张，缓存共享
internal const val COVER_PREFETCH_RADIUS = 500

// 并发 4，过高会在 MediaProvider 排队
internal const val COVER_PREFETCH_CONCURRENCY = 4

// 空转轮询 500ms，兜住封面重写后的重扫
private const val COVER_PREFETCH_IDLE_POLL_MS = 500L

/**
 * 列表封面邻域预取：前后各 500 项提前解码，取图由近及远。
 * 窗口发布与取图循环分离，滚动只换下一批，不打断在飞的一批。
 */
@Composable
internal fun CoverPrefetch(
    listState: LazyListState,
    trackAt: (Int) -> MusicTrack?,
) {
    val context = LocalContext.current
    val sizePx = listCoverThumbnailSize(LocalDensity.current)
    // 按最新映射解析下标
    val currentTrackAt by rememberUpdatedState(trackAt)
    LaunchedEffect(listState, context, sizePx) {
        // 只发布最新窗口，StateFlow 自动合并
        val visible = MutableStateFlow(IntRange.EMPTY)
        launch {
            snapshotFlow { visibleRange(listState) }.collect { visible.value = it }
        }
        while (isActive) {
            val range = visible.value
            if (range.isEmpty()) {
                // 可视项为空时等窗口出现
                visible.awaitChange(range)
                continue
            }
            val forward = listState.lastScrolledForward
            val order = prefetchOrder(range, forward, listState.layoutInfo.totalItemsCount)
            prefetchCoverBatch(
                context = context,
                sizePx = sizePx,
                order = order,
                trackAt = currentTrackAt,
                // 窗口移走不再开新批，已发出的不取消
                isStale = { visible.value != range },
            )
            // 整窗走完再等窗口变化或轮询
            if (visible.value == range) visible.awaitChange(range)
        }
    }
}

/**
 * 按顺序分批并发预取，已缓存项跳过，单张失败不打断
 */
internal suspend fun prefetchCoverBatch(
    context: Context,
    sizePx: Int,
    order: List<Int>,
    trackAt: (Int) -> MusicTrack?,
    isStale: () -> Boolean = { false },
) {
    var start = 0
    while (start < order.size && !isStale()) {
        val end = minOf(start + COVER_PREFETCH_CONCURRENCY, order.size)
        coroutineScope {
            (start until end).map { position ->
                async { prefetchCover(context, sizePx, order[position], trackAt) }
            }.awaitAll()
        }
        start = end
    }
}

// 下标落空或已缓存即跳过
private suspend fun prefetchCover(
    context: Context,
    sizePx: Int,
    index: Int,
    trackAt: (Int) -> MusicTrack?,
) {
    val track = trackAt(index) ?: return
    if (MusicCoverLoader.cachedThumbnail(track, sizePx) != null) return
    try {
        MusicCoverLoader.prefetch(context, track, sizePx)
    } catch (_: CancellationException) {
        // 共享取图被撤时的取消，跳过本项
    }
}

// 可视区；未布局时返回空区间
private fun visibleRange(listState: LazyListState): IntRange {
    val visible = listState.layoutInfo.visibleItemsInfo
    if (visible.isEmpty()) return IntRange.EMPTY
    return visible.first().index..visible.last().index
}

/**
 * 预取顺序：由近及远，同距优先进入视口的一侧
 */
private fun prefetchOrder(visible: IntRange, forward: Boolean, total: Int): List<Int> {
    val order = ArrayList<Int>(minOf(total, COVER_PREFETCH_RADIUS * 2 + 1))
    for (index in visible.first..visible.last) if (index in 0 until total) order += index
    for (offset in 1..COVER_PREFETCH_RADIUS) {
        val below = visible.last + offset
        val above = visible.first - offset
        val leading = if (forward) below else above
        val trailing = if (forward) above else below
        if (leading in 0 until total) order += leading
        if (trailing in 0 until total) order += trailing
    }
    return order
}

// 等窗口变化或轮询到期
private suspend fun MutableStateFlow<IntRange>.awaitChange(current: IntRange) {
    withTimeoutOrNull(COVER_PREFETCH_IDLE_POLL_MS) { first { it != current } }
}
