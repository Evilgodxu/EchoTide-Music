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

// 预取窗口半径：可视区（或当前曲目）前后各取这么多项，整窗约千张。
// 该量级在内存缓存预算之内（列表行档位单张约二三十 KB，千张约 30 MB），
// 且缓存按曲目全局共享 —— 一处取到的封面，任何正在显示同一首歌的列表都直接命中，
// 放宽窗口的收益因此不止落在发起预取的那个列表
internal const val COVER_PREFETCH_RADIUS = 500

// 同时在取的张数：系统略缩图的查询与解码都落在 IO 上，少量并发即可打满，
// 更高只会让请求在 MediaProvider 前排长队，反而推迟最近邻项的到位
internal const val COVER_PREFETCH_CONCURRENCY = 4

// 空转时的重比对周期：窗口没动、窗口内封面也都已就位时，按此周期重扫一次。
// 封面重写会清空内存缓存，此时窗口不动，靠窗口变化唤不醒取图循环
private const val COVER_PREFETCH_IDLE_POLL_MS = 500L

/**
 * 列表封面邻域预取：把 [listState] 可视区前后各 [COVER_PREFETCH_RADIUS] 项的封面提前解码进内存缓存，
 * 使用户滚动到该项时封面已就位（首帧即同步命中内存缓存，见 rememberSystemThumbnail），不再先闪占位符。
 *
 * 窗口始终以可视区为中心：滚动把它整体带走，循环随即按新窗口重排，取图始终追着用户正在看的那一段走。
 *
 * 由两半构成，两者之间只传「当前可视区」这一个最新值：
 * - **窗口发布**：把可视区算成一个区间发布出去，滚动中每次跨行都可能更新；
 * - **取图循环**：一个常驻循环，滚动再快也不重启。
 *
 * 拆成两半正是原先失效的地方：原先用 collectLatest 把「取整个邻域」当成一次可取消的批处理，
 * 窗口一变就整批取消重来。滚动中窗口频繁变化，于是每一轮都只开了个头就被打断 ——
 * 实际吞吐趋近于零，预取形同虚设，滚动时封面全靠各行自己按需加载，占位符自然闪个不停。
 * 拆开之后，窗口怎么变都只影响「下一批取谁」，已发出的一批继续在飞，取图始终在推进。
 *
 * 取图顺序按「离可视区由近及远」，同距时优先取将要进入视口的一侧（按 [LazyListState.lastScrolledForward]）：
 * 可视区内的项眼下正显示占位符，取到即消除闪烁，排在最前。
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
            val order = prefetchOrder(range, forward, listState.layoutInfo.totalItemsCount)
            prefetchCoverBatch(
                context = context,
                sizePx = sizePx,
                order = order,
                trackAt = currentTrackAt,
                // 窗口移走即不再开新批：已发出的那一批不取消，它已付出解码代价、结果也已进内存缓存，
                // 取消还会连带打断正在等同一张图的可视行
                isStale = { visible.value != range },
            )
            // 整窗走完（中途未因窗口变化而中断）说明这一轮已把能取的都取了：
            // 等到窗口变化或轮询周期到再重扫，避免空转。窗口已变则直接进入下一轮
            if (visible.value == range) visible.awaitChange(range)
        }
    }
}

/**
 * 按 [order] 顺序并发预取封面，每 [COVER_PREFETCH_CONCURRENCY] 项一批，每批之间问一次 [isStale]：
 * 顺序即优先级，最近邻项先开取；窗口被移走后不再开新批，在飞的那一批跑完。
 *
 * 已驻留内存缓存的项直接跳过（判定与显示端同一口径，见 [MusicCoverLoader.cachedThumbnail]）：
 * 窗口内绝大多数项在滚动中已就位，跳过它们才让每次重扫都只是「扫过」而不是「重新取图」。
 *
 * 单张取不到不打断整批：交由显示端滚入视口时按需重试。
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

// 取单项：下标落空、或已驻留内存缓存即跳过
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
        // 同一张图常同时被可视行在取，行离开视口会撤掉那次共享取图，等待中的这里因此收到取消。
        // 那不是本批被取消 —— 跳过这一项，后面的照常取
    }
}

// 当前可视区：可视项为空（列表尚未布局）时返回空区间
private fun visibleRange(listState: LazyListState): IntRange {
    val visible = listState.layoutInfo.visibleItemsInfo
    if (visible.isEmpty()) return IntRange.EMPTY
    return visible.first().index..visible.last().index
}

/**
 * 预取顺序：窗口以可视区为中心前后各扩 [COVER_PREFETCH_RADIUS] 项，按离可视区由近及远排列；
 * 同距时优先取将要进入可视区的一侧 —— 向下滚动时是列表尾部一侧，向上滚动时是头部一侧。
 * 超出 [total] 的下标不入窗口，列表比窗口短时不必空扫。
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

// 等到可视区变化，或轮询周期到。窗口未变化时不被唤醒的会是封面重写后的重取需求，故不能无限等
private suspend fun MutableStateFlow<IntRange>.awaitChange(current: IntRange) {
    withTimeoutOrNull(COVER_PREFETCH_IDLE_POLL_MS) { first { it != current } }
}
