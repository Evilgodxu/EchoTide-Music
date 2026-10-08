package com.yichao.evilgodxu.ui.component

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// 可视区前后各预取的项数：约等于一屏的行数，保证慢速滚动时下一屏的封面已就绪。
// 越界部分不会取图，故无需与列表总长关联
private const val COVER_PREFETCH_MARGIN = 8

// 空转时的重比对周期：窗口没动、窗口内封面也都已就位时，按此周期重扫一次。
// 封面重写会清空内存缓存，此时窗口不动，靠窗口变化唤不醒取图循环
private const val COVER_PREFETCH_IDLE_POLL_MS = 500L

/**
 * 列表封面邻域预取：把 [listState] 可视区前后各 [COVER_PREFETCH_MARGIN] 项的封面提前解码进内存缓存，
 * 使用户滚动到该项时封面已就位（首帧即同步命中内存缓存，见 rememberSystemThumbnail），不再先闪占位符再出图。
 *
 * 由两半构成，两者之间只传「当前可视区」这一个最新值：
 * - **窗口发布**：把可视区算成一个区间发布出去，滚动中每帧都可能更新；
 * - **取图循环**：一个常驻循环，滚动再快也不重启。
 *
 * 拆成两半正是原先失效的地方：原先用 collectLatest 把「取整个邻域」当成一次可取消的批处理，
 * 窗口一变就整批取消重来。滚动中窗口每帧都在变，于是每一轮都只开了个头就被打断 ——
 * 实际吞吐趋近于零，预取形同虚设，滚动时封面全靠各行自己按需加载，占位符自然闪个不停。
 * 拆开之后，窗口怎么变都只影响「下一项取谁」，已发出的那一项继续在飞，取图始终在推进。
 *
 * 取图顺序按「离可视区由近及远」，同距时优先取将要进入视口的一侧（按 [LazyListState.lastScrolledForward]）：
 * 可视区内的项眼下正显示占位符，取到即消除闪烁，排在最前；越界部分只取到 [COVER_PREFETCH_MARGIN] 为止。
 * 逐项串行而不并发：单张系统略缩图的解码在数十毫秒量级，正常滚动（每秒数行）远用不上更高吞吐，
 * 而串行可保证先取到的总是最近邻项，也不会与可视区各行自己的加载争抢系统略缩图查询。
 *
 * [trackAt] 按列表项下标解析封面曲目：下标落空（头部项、越界）返回 null 即跳过。
 * 请求档位取列表行封面的口径，须与列表行实际使用的 [PlaylistArt] 保持一致，否则预取结果无法被行命中。
 */
@Composable
internal fun CoverPrefetch(
    listState: LazyListState,
    trackAt: (Int) -> MusicTrack?,
) {
    val context = LocalContext.current
    val sizePx = listCoverThumbnailSize(LocalDensity.current)
    // 列表内容会随排序、筛选、拖拽重排重建映射，取图始终按最新映射解析下标
    val currentTrackAt by rememberUpdatedState(trackAt)
    LaunchedEffect(listState, context, sizePx) {
        // 只发布最新值：StateFlow 自带合并，滚动中一帧多次更新也只留下最后那个，
        // 取图循环读到的永远是当前窗口，不会逐个去追历史窗口
        val visible = MutableStateFlow(IntRange.EMPTY)
        launch {
            snapshotFlow { visibleRange(listState) }.collect { visible.value = it }
        }
        while (isActive) {
            val range = visible.value
            if (range.isEmpty()) {
                // 列表尚未布局（可视项为空）：等窗口出现再开始
                visible.awaitChange(range)
                continue
            }
            val forward = listState.lastScrolledForward
            for (index in prefetchOrder(range, forward)) {
                // 取图期间可视区整体移走：立刻按新窗口重排。已发出的那一项不取消 ——
                // 它已付出解码代价，结果也已进内存缓存，取消只会把这份代价白扔
                if (visible.value != range) break
                val track = currentTrackAt(index) ?: continue
                if (MusicCoverLoader.cachedThumbnail(track, sizePx) != null) continue
                MusicCoverLoader.prefetch(context, track, sizePx)
            }
            // 整窗走完（中途未因窗口变化而中断）说明这一轮已把能取的都取了：
            // 等到窗口变化或轮询周期到再重扫，避免空转。窗口已变则直接进入下一轮
            if (visible.value == range) visible.awaitChange(range)
        }
    }
}

// 当前可视区：可视项为空（列表尚未布局）时返回空区间
private fun visibleRange(listState: LazyListState): IntRange {
    val visible = listState.layoutInfo.visibleItemsInfo
    if (visible.isEmpty()) return IntRange.EMPTY
    return visible.first().index..visible.last().index
}

/**
 * 预取顺序：可视区前后各扩 [COVER_PREFETCH_MARGIN] 项，按离可视区由近及远排列。
 * 同距时优先取将要进入可视区的一侧 —— 向下滚动时是列表尾部一侧，向上滚动时是头部一侧。
 */
private fun prefetchOrder(visible: IntRange, forward: Boolean): List<Int> {
    val first = visible.first - COVER_PREFETCH_MARGIN
    val last = visible.last + COVER_PREFETCH_MARGIN
    return (first..last).sortedWith(
        compareBy(
            { index -> distanceToViewport(visible, index) },
            { index -> sidePriority(visible, index, forward) },
        )
    )
}

// 距可视区的项数：可视区内为 0（眼下正显示占位符，最该先取）
private fun distanceToViewport(visible: IntRange, index: Int): Int = when {
    index < visible.first -> visible.first - index
    index > visible.last -> index - visible.last
    else -> 0
}

// 同距时的两侧次序：0 为「将要进入可视区」的一侧
private fun sidePriority(visible: IntRange, index: Int, forward: Boolean): Int = when {
    index > visible.last -> if (forward) 0 else 1
    index < visible.first -> if (forward) 1 else 0
    else -> 0
}

// 等到可视区变化，或轮询周期到。窗口未变化时不被唤醒的会是封面重写后的重取需求，故不能无限等
private suspend fun MutableStateFlow<IntRange>.awaitChange(current: IntRange) {
    withTimeoutOrNull(COVER_PREFETCH_IDLE_POLL_MS) { first { it != current } }
}
