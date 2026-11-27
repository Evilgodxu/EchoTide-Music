package com.yichao.evilgodxu.ui.component

import androidx.compose.foundation.lazy.LazyListState

// 距可见项 10 项内平滑滚动，否则直接跳
private const val SCROLL_NEAR_ITEM_RANGE = 10

/**
 * 定位到指定曲目并居中；已在可视区且不强制时不动
 */
internal suspend fun LazyListState.scrollPlaylistTo(index: Int, forceCenter: Boolean = false) {
    val layout = layoutInfo
    val visible = layout.visibleItemsInfo
    if (!forceCenter && visible.any { it.index == index }) return
    val scrollOffset = centerScrollOffset()
    val first = visible.firstOrNull()?.index
    val last = visible.lastOrNull()?.index
    if (first != null && last != null &&
        index in (first - SCROLL_NEAR_ITEM_RANGE)..(last + SCROLL_NEAR_ITEM_RANGE)
    ) {
        animateScrollToItem(index, scrollOffset = scrollOffset)
    } else {
        scrollToItem(index, scrollOffset = scrollOffset)
    }
}

// 居中所需偏移，未布局时退化为顶部对齐
private fun LazyListState.centerScrollOffset(): Int {
    val layout = layoutInfo
    val viewportHeight = layout.viewportEndOffset - layout.viewportStartOffset
    val itemHeight = layout.visibleItemsInfo.firstOrNull()?.size ?: return 0
    return -(viewportHeight - itemHeight) / 2
}
