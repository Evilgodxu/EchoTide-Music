package com.yichao.evilgodxu.ui.component

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.yichao.evilgodxu.theme.AppAlpha
import com.yichao.evilgodxu.theme.AppShape
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

// 菜单展开方向
enum class ExpandDirection { Up, Down, Right }

/**
 * 展开式选择器：点击展开，选中或点外部收回。
 * 触发区由 trigger 承载，菜单自锚点一侧逐项浮现。
 * maxMenuHeight 限制高度，超出则滚动。
 */
@Composable
fun <T> ExpandPicker(
    options: List<T>,
    selected: T?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    expandDirection: ExpandDirection = ExpandDirection.Up,
    horizontalAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    maxMenuHeight: Dp? = null,
    // 默认抬升色阶，与对话框容器区分
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    containerShape: Shape = AppShape.Control,
    itemShape: Shape = AppShape.Micro,
    itemHighlightColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = AppAlpha.Selected),
    trigger: @Composable () -> Unit,
    itemContent: @Composable (option: T, selected: Boolean) -> Unit,
    onItemClick: (T) -> Unit,
    header: (@Composable () -> Unit)? = null,
) {
    // 展开意图；弹窗在收起动画播完才卸载
    var expanded by remember { mutableStateOf(false) }
    val visibleState = remember { MutableTransitionState(false) }
    visibleState.targetState = expanded

    // 触发区上下缘，用于限高
    var triggerTopPx by remember { mutableFloatStateOf(0f) }
    var triggerBottomPx by remember { mutableFloatStateOf(0f) }
    val windowHeightPx = LocalWindowInfo.current.containerSize.height
    val density = LocalDensity.current
    // 上限取调用方与锚点侧可用空间的较小值
    val anchoredMaxHeight = maxMenuHeight?.let { cap ->
        val gapPx = with(density) { MENU_GAP_DP.toPx() }
        val marginPx = with(density) { HORIZONTAL_MARGIN_DP.toPx() }
        val availablePx = when (expandDirection) {
            ExpandDirection.Down -> windowHeightPx - triggerBottomPx - gapPx - marginPx
            ExpandDirection.Up -> triggerTopPx - gapPx - marginPx
            ExpandDirection.Right -> Float.MAX_VALUE
        }
        val available = with(density) { availablePx.toDp() }
        if (available > 0.dp && available < cap) available else cap
    }

    Box(
        modifier = modifier
            .onGloballyPositioned {
                val bounds = it.boundsInWindow()
                triggerTopPx = bounds.top
                triggerBottomPx = bounds.bottom
            }
            .combinedClickable(
                enabled = enabled,
                onClick = { expanded = !expanded },
            ),
    ) {
        trigger()

        if (visibleState.currentState || visibleState.targetState) {
            Popup(
                onDismissRequest = { expanded = false },
                popupPositionProvider = expandPositionProvider(
                    direction = expandDirection,
                    horizontalAlignment = horizontalAlignment,
                    verticalAlignment = verticalAlignment,
                    // 限高菜单不翻转，留在本侧
                    allowFlip = anchoredMaxHeight == null,
                ),
                properties = PopupProperties(
                    focusable = true,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true,
                ),
            ) {
                ExpandMenuContent(
                    visibleState = visibleState,
                    direction = expandDirection,
                    options = options,
                    selected = selected,
                    header = header,
                    maxMenuHeight = anchoredMaxHeight,
                    containerColor = containerColor,
                    containerShape = containerShape,
                    itemShape = itemShape,
                    itemHighlightColor = itemHighlightColor,
                    itemContent = itemContent,
                    onItemClick = onItemClick,
                    onCollapse = { expanded = false },
                )
            }
        }
    }
}

/**
 * 菜单主体：容器自锚点一侧裁切展开，选项逐项浮现。
 * 容器与选项共用同一进度轴，天然同步。
 * 裁切不改布局尺寸，避免弹窗定位抖动。
 */
@Composable
private fun <T> ExpandMenuContent(
    visibleState: MutableTransitionState<Boolean>,
    direction: ExpandDirection,
    options: List<T>,
    selected: T?,
    header: (@Composable () -> Unit)?,
    maxMenuHeight: Dp?,
    containerColor: Color,
    containerShape: Shape,
    itemShape: Shape,
    itemHighlightColor: Color,
    itemContent: @Composable (T, Boolean) -> Unit,
    onItemClick: (T) -> Unit,
    onCollapse: () -> Unit,
) {
    val transition = rememberTransition(visibleState, label = "ExpandPicker")
    // 唯一进度源，容器与选项共用
    val progress by transition.animateFloat(
        transitionSpec = { tween(MENU_REVEAL_MS) },
        label = "revealProgress",
    ) { expanded -> if (expanded) 1f else 0f }
    // 容器不透明度，先快速显形
    val containerAlpha = (progress / CONTAINER_FADE_PROGRESS).coerceIn(0f, 1f)

    // header 占第一个呈现位
    val headerCount = if (header != null) 1 else 0
    val itemCount = options.size + headerCount
    // 起始滚动位置取锚点一侧
    val scrollState = rememberScrollState(
        initial = if (direction == ExpandDirection.Up) Int.MAX_VALUE else 0,
    )

    Surface(
        modifier = Modifier
            .graphicsLayer { alpha = containerAlpha }
            .drawWithContent {
                // 自锚点一侧向外裁切
                when (direction) {
                    ExpandDirection.Up -> clipRect(
                        left = 0f,
                        top = size.height * (1f - progress),
                        right = size.width,
                        bottom = size.height,
                    ) { this@drawWithContent.drawContent() }

                    ExpandDirection.Down -> clipRect(
                        left = 0f,
                        top = 0f,
                        right = size.width,
                        bottom = size.height * progress,
                    ) { this@drawWithContent.drawContent() }

                    ExpandDirection.Right -> clipRect(
                        left = 0f,
                        top = 0f,
                        right = size.width * progress,
                        bottom = size.height,
                    ) { this@drawWithContent.drawContent() }
                }
            },
        shape = containerShape,
        color = containerColor,
    ) {
        Column(
            modifier = Modifier
                // 上限含内边距，受限时接入滚动
                .then(if (maxMenuHeight != null) Modifier.heightIn(max = maxMenuHeight) else Modifier)
                .padding(MENU_PADDING)
                // 以最宽选项为公共宽度
                .width(IntrinsicSize.Max)
                .then(if (maxMenuHeight != null) Modifier.verticalScroll(scrollState) else Modifier),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (header != null) {
                ExpandMenuRow(
                    progress = progress,
                    order = anchorOrder(direction, position = 0, itemCount = itemCount),
                    itemCount = itemCount,
                ) {
                    header()
                }
            }
            options.forEachIndexed { index, option ->
                val isSelected = option == selected
                ExpandMenuRow(
                    progress = progress,
                    order = anchorOrder(direction, position = index + headerCount, itemCount = itemCount),
                    itemCount = itemCount,
                    shape = itemShape,
                    background = if (isSelected) itemHighlightColor else Color.Transparent,
                    onClick = {
                        onItemClick(option)
                        onCollapse()
                    },
                ) {
                    itemContent(option, isSelected)
                }
            }
        }
    }
}

// 呈现位换算为距锚点远近，Up 需倒置
private fun anchorOrder(direction: ExpandDirection, position: Int, itemCount: Int): Int =
    if (direction == ExpandDirection.Up) itemCount - 1 - position else position

// 距锚点越近浮现越早，末项对齐进度 1
private fun itemRevealAlpha(progress: Float, order: Int, itemCount: Int): Float {
    val firstStart = ITEM_REVEAL_LEAD_IN
    val lastStart = (1f - ITEM_REVEAL_SPAN).coerceAtLeast(firstStart)
    val start = if (itemCount <= 1) firstStart
    else firstStart + (lastStart - firstStart) * order / (itemCount - 1)
    return ((progress - start) / ITEM_REVEAL_SPAN).coerceIn(0f, 1f)
}

// 单项仅改透明度，始终参与布局
@Composable
private fun ExpandMenuRow(
    progress: Float,
    order: Int,
    itemCount: Int,
    modifier: Modifier = Modifier,
    shape: Shape? = null,
    background: Color = Color.Transparent,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val itemAlpha = itemRevealAlpha(progress, order, itemCount)
    var rowModifier = Modifier
        .fillMaxWidth()
        .clip(shape ?: RoundedCornerShape(0.dp))
        .background(background)
        .graphicsLayer { alpha = itemAlpha }
    if (onClick != null) {
        rowModifier = rowModifier.clickable { onClick() }
    }
    Box(modifier = rowModifier.then(modifier)) {
        content()
    }
}

// 按方向贴锚点，空间不足时翻转并夹在窗口内
@Composable
private fun expandPositionProvider(
    direction: ExpandDirection,
    horizontalAlignment: Alignment.Horizontal,
    verticalAlignment: Alignment.Vertical,
    allowFlip: Boolean,
): PopupPositionProvider {
    val density = LocalDensity.current
    return remember(
        density,
        direction,
        horizontalAlignment,
        verticalAlignment,
        allowFlip,
    ) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val gapPx = with(density) { MENU_GAP_DP.roundToPx() }
                val marginPx = with(density) { HORIZONTAL_MARGIN_DP.roundToPx() }
                val width = popupContentSize.width
                val height = popupContentSize.height
                val clampX = { rawX: Int ->
                    rawX.coerceIn(marginPx, (windowSize.width - width - marginPx).coerceAtLeast(marginPx))
                }
                val clampY = { rawY: Int ->
                    rawY.coerceIn(marginPx, (windowSize.height - height - marginPx).coerceAtLeast(marginPx))
                }
                val horizontalX = when (horizontalAlignment) {
                    Alignment.Start -> anchorBounds.left
                    Alignment.End -> anchorBounds.right - width
                    else -> anchorBounds.left + (anchorBounds.width - width) / 2
                }
                val verticalY = when (verticalAlignment) {
                    Alignment.Top -> anchorBounds.top
                    Alignment.Bottom -> anchorBounds.bottom - height
                    else -> anchorBounds.top + (anchorBounds.height - height) / 2
                }
                val x: Int
                val y: Int
                when (direction) {
                    ExpandDirection.Up -> {
                        val above = anchorBounds.top - height - gapPx
                        y = if (above >= 0 || !allowFlip) above else anchorBounds.bottom + gapPx
                        x = horizontalX
                    }
                    ExpandDirection.Down -> {
                        val below = anchorBounds.bottom + gapPx
                        // 下方放不下且允许翻转时改到上方
                        y = if (below + height <= windowSize.height || !allowFlip) {
                            below
                        } else {
                            anchorBounds.top - height - gapPx
                        }
                        x = horizontalX
                    }
                    ExpandDirection.Right -> {
                        x = if (anchorBounds.right + width + gapPx <= windowSize.width) {
                            anchorBounds.right + gapPx
                        } else {
                            anchorBounds.left - width - gapPx
                        }
                        y = verticalY
                    }
                }
                return IntOffset(x = clampX(x), y = clampY(y))
            }
        }
    }
}

// 展开总时长 560ms，裁切与逐项浮现共用
private const val MENU_REVEAL_MS = 560
// 容器透明显形到 1 的进度比例
private const val CONTAINER_FADE_PROGRESS = 0.3f
// 首项浮现前的进度留白
private const val ITEM_REVEAL_LEAD_IN = 0.12f
// 单项浮现占用的进度窗口
private const val ITEM_REVEAL_SPAN = 0.3f
// 菜单容器内边距
private val MENU_PADDING = 4.dp
// 菜单与触发区间隔
private val MENU_GAP_DP = 4.dp
// 菜单距屏幕边缘最小边距
private val HORIZONTAL_MARGIN_DP = 8.dp