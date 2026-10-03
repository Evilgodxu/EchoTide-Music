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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

// 可视区前后各预取的项数：约等于一屏的行数，保证慢速滚动时下一屏的封面已就绪。
// 越界部分不会取图，故无需与列表总长关联
private const val COVER_PREFETCH_MARGIN = 8

/**
 * 列表封面邻域预取：把 [listState] 可视区前后各 [COVER_PREFETCH_MARGIN] 项的封面提前解码进内存缓存，
 * 使用户滚动到该项时封面已就位，不再先闪占位符再出图。
 *
 * 只覆盖可视区邻域：更远的项仍由各行滚入视口时按需加载，长列表不会一次性铺满内存与系统略缩图查询队列。
 * 邻域窗口在滚动中整体替换，只有最新窗口参与取图，过期窗口随协程取消丢弃。
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
        snapshotFlow {
            val visible = listState.layoutInfo.visibleItemsInfo
            if (visible.isEmpty()) {
                IntRange.EMPTY
            } else {
                (visible.first().index - COVER_PREFETCH_MARGIN)..(visible.last().index + COVER_PREFETCH_MARGIN)
            }
        }
            .distinctUntilChanged()
            .collectLatest { range ->
                val tracks = range.mapNotNull { currentTrackAt(it) }
                MusicCoverLoader.prefetch(context, tracks, sizePx)
            }
    }
}
