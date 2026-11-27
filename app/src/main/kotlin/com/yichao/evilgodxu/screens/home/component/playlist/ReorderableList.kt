package com.yichao.evilgodxu.screens.home.component.playlist

import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** 可排序状态：跟踪拖拽项，算视觉偏移，互斥锁下重排 */
@Stable
internal class ReorderableLazyListState internal constructor(
    private val listState: LazyListState,
    private val scope: CoroutineScope,
    private val onMove: (from: Int, to: Int) -> Unit,
) {
    private val onMoveMutex = Mutex()
    private var draggingItemKey by mutableStateOf<Any?>(null)
    private var draggingItemDraggedDelta by mutableStateOf(Offset.Zero)
    private var draggingItemInitialOffset by mutableStateOf(IntOffset.Zero)
    private var oldDraggingItemIndex by mutableStateOf<Int?>(null)
    private var predictedDraggingItemOffset by mutableStateOf<IntOffset?>(null)
    // 拖拽到边缘时的常驻自动滚动协程
    private var autoScrollJob: Job? = null

    private val draggingItemInfo: LazyListItemInfo?
        get() = draggingItemKey?.let { key ->
            listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }
        }

    internal val isAnyItemDragging by derivedStateOf { draggingItemKey != null }

    /** 拖拽偏移＝手指位移 − 布局位移，抵消后跟手 */
    internal val draggingItemOffset: Offset
        get() {
            val info = draggingItemInfo ?: return Offset.Zero
            val offsetPx = if (info.index != oldDraggingItemIndex || oldDraggingItemIndex == null) {
                oldDraggingItemIndex = null
                predictedDraggingItemOffset = null
                info.offset
            } else {
                predictedDraggingItemOffset?.y ?: info.offset
            }
            // 手指位移减布局位移，保证跟手
            return draggingItemDraggedDelta + Offset(0f, (draggingItemInitialOffset.y - offsetPx).toFloat())
        }

    internal fun onDragStart(key: Any) {
        draggingItemKey = key
        draggingItemInitialOffset = IntOffset(0, draggingItemInfo?.offset ?: 0)
    }

    internal fun onDragStop() {
        draggingItemDraggedDelta = Offset.Zero
        draggingItemKey = null
        oldDraggingItemIndex = null
        predictedDraggingItemOffset = null
        autoScrollJob?.cancel()
        autoScrollJob = null
    }

    internal fun onDrag(dragAmount: Offset) {
        draggingItemDraggedDelta += dragAmount
        val draggingItem = draggingItemInfo ?: return
        // 进入边缘带即启动自动滚动
        updateAutoScroll()
        // 自动滚动期间跳过交集重排，避免冲突
        if (autoScrollJob?.isActive == true) return
        if (!onMoveMutex.tryLock()) return
        try {
            val startOffset = draggingItem.offset + draggingItemOffset.y.toInt()
            val endOffset = startOffset + draggingItem.size
            val target = listState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                item.key != draggingItem.key &&
                    item.offset + item.size / 2 >= startOffset &&
                    item.offset + item.size / 2 < endOffset
            }
            if (target != null && target.index != draggingItem.index) {
                scope.launch { moveItems(draggingItem, target) }
            }
        } finally {
            onMoveMutex.unlock()
        }
    }

    /**
     * 进入上下边缘带时启动常驻滚动，移出即停。
     * 每帧重判方向并推进拖拽项，避免脱离跟随。
     */
    private fun updateAutoScroll() {
        val shouldScroll = autoScrollDirection() != 0
        val scrolling = autoScrollJob?.isActive == true
        when {
            shouldScroll && !scrolling -> {
                autoScrollJob?.cancel()
                autoScrollJob = scope.launch { autoScrollLoop() }
            }
            !shouldScroll && scrolling -> {
                autoScrollJob?.cancel()
                autoScrollJob = null
            }
        }
    }

    // 手柄中心是否进入边缘带，返回方向
    private fun autoScrollDirection(): Int {
        val item = draggingItemInfo ?: return 0
        val viewportHeight = listState.layoutInfo.viewportSize.height
        // 手柄中心＝布局偏移 + 拖拽位移 + 半高
        val handleCenter = item.offset + (draggingItemOffset.y + item.size / 2f)
        val threshold = item.size * 1.2f
        return when {
            handleCenter < threshold && listState.canScrollBackward -> -1
            handleCenter > viewportHeight - threshold && listState.canScrollForward -> 1
            else -> 0
        }
    }

    // 每帧推进 0.5x..1.5x 行高，越靠边缘越快
    private fun autoScrollStep(): Float {
        val item = draggingItemInfo ?: return 0f
        val viewportHeight = listState.layoutInfo.viewportSize.height
        val handleCenter = item.offset + (draggingItemOffset.y + item.size / 2f)
        val threshold = item.size * 1.2f
        val depth = when {
            handleCenter < threshold -> (threshold - handleCenter) / threshold
            handleCenter > viewportHeight - threshold -> (handleCenter - (viewportHeight - threshold)) / threshold
            else -> 0.5f
        }.coerceIn(0f, 1f)
        return item.size * (0.5f + depth)
    }

    private suspend fun autoScrollLoop() {
        // 由 updateAutoScroll 在移出边缘时取消
        while (true) {
            val dir = autoScrollDirection()
            if (dir == 0) break
            // 先把拖拽项归位到边缘再滚动，避免中断
            moveDraggingItemToEnd(dir)
            listState.animateScrollBy(autoScrollStep() * dir)
            delay(AUTO_SCROLL_FRAME_MS)
        }
        autoScrollJob = null
    }

    // 把拖拽项重排到滚动方向边缘，保持跟随
    private suspend fun moveDraggingItemToEnd(dir: Int) {
        val dragged = draggingItemInfo ?: return
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.isEmpty()) return
        val lead = if (dir < 0) visible.minBy { it.index } else visible.maxBy { it.index }
        // 已处于滚动方向的边缘，无需再移动
        if (dragged.index == lead.index) return
        // 仅允许朝滚动方向推进，避免反向
        if (dir < 0 && lead.index > dragged.index) return
        if (dir > 0 && lead.index < dragged.index) return
        moveItems(dragged, lead)
    }

    private suspend fun moveItems(
        from: LazyListItemInfo,
        to: LazyListItemInfo,
    ) {
        try {
            onMoveMutex.withLock {
                if (draggingItemKey == null) return
                // 触及首项时重锚滚动位置，避免顶出
                if (from.index == listState.firstVisibleItemIndex ||
                    to.index == listState.firstVisibleItemIndex
                ) {
                    listState.requestScrollToItem(
                        listState.firstVisibleItemIndex,
                        listState.firstVisibleItemScrollOffset,
                    )
                }
                oldDraggingItemIndex = from.index
                onMove(from.index, to.index)
                predictedDraggingItemOffset = if (to.index > from.index) {
                    IntOffset(0, to.offset + to.size - from.size)
                } else {
                    IntOffset(0, to.offset)
                }
                try {
                    // 等布局落定，避免错帧抖动
                    withTimeout(ReorderableStateLayoutInfoUpdateMaxWaitDuration) {
                        snapshotFlow { listState.layoutInfo }.take(2).collect()
                    }
                } finally {
                    oldDraggingItemIndex = null
                    predictedDraggingItemOffset = null
                }
            }
        } catch (e: CancellationException) {
            // 重排布局更新等待被取消，属于预期
        }
    }

    internal fun isItemDragging(key: Any): State<Boolean> = derivedStateOf { key == draggingItemKey }

    companion object {
        const val ReorderableStateLayoutInfoUpdateMaxWaitDuration = 1000L
        private const val AUTO_SCROLL_FRAME_MS = 16L
    }
}

/**
 * 创建可排序状态；onMove 在需移动时按交集调用。
 */
@Composable
internal fun rememberReorderableLazyListState(
    listState: LazyListState,
    onMove: (from: Int, to: Int) -> Unit,
): ReorderableLazyListState {
    val scope = rememberCoroutineScope()
    val currentOnMove by rememberUpdatedState(onMove)
    return remember(listState) {
        ReorderableLazyListState(
            listState = listState,
            scope = scope,
            onMove = { from, to -> currentOnMove(from, to) },
        )
    }
}

/**
 * 拖拽项跟手，其余项 animateItem 让位。
 */
@Composable
internal fun LazyItemScope.ReorderableItem(
    state: ReorderableLazyListState,
    key: Any,
    content: @Composable (isDragging: Boolean) -> Unit,
) {
    val dragging by state.isItemDragging(key)
    val offsetModifier = if (dragging) {
        Modifier
            .zIndex(1f)
            .graphicsLayer {
                translationY = state.draggingItemOffset.y
            }
    } else {
        Modifier.animateItem(
            placementSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow,
            )
        )
    }
    Box(modifier = offsetModifier) {
        content(dragging)
    }
}

/**
 * 拖拽手柄：先消费按下，长按后才开始排序。
 */
internal fun Modifier.longPressDraggableHandle(
    state: ReorderableLazyListState,
    key: Any,
): Modifier = pointerInput(state, key) {
    awaitEachGesture {
        // 先消费按下，避免行级长按误触发
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        val longPress = awaitLongPressOrCancellation(down.id)
        if (longPress != null) {
            state.onDragStart(key)
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    change.consume()
                    val delta = change.position - change.previousPosition
                    if (delta != Offset.Zero) state.onDrag(delta)
                }
            } finally {
                state.onDragStop()
            }
        }
    }
}
