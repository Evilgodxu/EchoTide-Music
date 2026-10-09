package com.yichao.evilgodxu.ui.component

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.abs

// 选择器菜单的展开方向：决定菜单相对触发区域的位置与逐项展开的进入方向
enum class ExpandDirection { Up, Down, Right }

/**
 * 展开式选择器：点击、沿展开方向滑动展开，反向滑动收起，点击其他区域自动收回。
 *
 * 触发区域由 [trigger] 槽位承载，其大小决定触控热区；展开后菜单自触发一侧缩放展开，
 * 选项按顺序逐项浮现，收起时逐项隐去。选项内容由 [itemContent] 注入，选中态（背景高亮）
 * 由本组件统一处理，前景配色由注入内容按 selected 自行决定。跨页面复用，故上提至 ui/component/。
 *
 * [horizontalAlignment] 在 [ExpandDirection.Up] / [ExpandDirection.Down] 下控制菜单相对触发区域
 * 的水平落位；[verticalAlignment] 在 [ExpandDirection.Right] 下控制垂直落位。
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
    containerColor: Color = MaterialTheme.colorScheme.surface,
    containerShape: Shape = RoundedCornerShape(10.dp),
    itemShape: Shape = RoundedCornerShape(6.dp),
    itemHighlightColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
    // 是否将菜单夹在锚定窗口内：悬浮窗等独立小窗口场景下窗口尺寸不等于屏幕，应关闭以免菜单被裁到窗口内
    clampToWindow: Boolean = true,
    trigger: @Composable () -> Unit,
    itemContent: @Composable (option: T, selected: Boolean) -> Unit,
    onItemClick: (T) -> Unit,
    header: (@Composable () -> Unit)? = null,
) {
    // 展开为逻辑意图，动画进度由动画库的过渡状态持有：currentState 为当前所处状态、
    // targetState 为目标状态。弹窗在两者任一为 true 期间保持挂载——收起动画播放时不卸载，
    // 播完自动回到 false 才移除，此前以计时器估算移除时机既不精确也会在末尾露出空容器
    var expanded by remember { mutableStateOf(false) }
    val visibleState = remember { MutableTransitionState(false) }
    visibleState.targetState = expanded

    val density = LocalDensity.current
    // 滑动主轴与展开方向一致：Right 用左右滑动，Up/Down 用上下滑动；展开沿主轴的正/负向
    val horizontal = expandDirection == ExpandDirection.Right
    val expandPositive = expandDirection != ExpandDirection.Up
    Box(
        modifier = modifier
            .combinedClickable(
                enabled = enabled,
                onClick = { expanded = !expanded },
            )
            .pointerInput(enabled, expandDirection) {
                if (!enabled) return@pointerInput
                detectSwipe(
                    thresholdPx = with(density) { SWIPE_TRIGGER_DISTANCE.toPx() },
                    horizontal = horizontal,
                    expandPositive = expandPositive,
                    onSwipeExpand = { expanded = true },
                    onSwipeCollapse = { expanded = false },
                )
            },
    ) {
        trigger()

        if (visibleState.currentState || visibleState.targetState) {
            Popup(
                onDismissRequest = { expanded = false },
                popupPositionProvider = expandPositionProvider(
                    direction = expandDirection,
                    horizontalAlignment = horizontalAlignment,
                    verticalAlignment = verticalAlignment,
                    clampToWindow = clampToWindow,
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
                    containerColor = containerColor,
                    containerShape = containerShape,
                    itemShape = itemShape,
                    itemHighlightColor = itemHighlightColor,
                    itemContent = itemContent,
                    onItemClick = onItemClick,
                )
            }
        }
    }
}

/**
 * 菜单主体：外层容器整体缩放并淡入淡出，内部选项按呈现位错峰淡入淡出。
 *
 * 选项始终参与组合与布局，仅以 [graphicsLayer] 调整透明度，容器尺寸在动画期间恒定——
 * 弹窗定位依赖内容尺寸，若动画改变尺寸会导致定位逐帧重算（抖动/闪烁的来源），
 * 且项不再因显隐进出组合树，容器也就不会在末尾露出空框。展开与收起由 [visibleState] 的过渡驱动：
 * 它由动画库创建，首次组合即为 current=false、target=true，因此不会出现「首帧已是展开态故不播动画」。
 */
@Composable
private fun <T> ExpandMenuContent(
    visibleState: MutableTransitionState<Boolean>,
    direction: ExpandDirection,
    options: List<T>,
    selected: T?,
    header: (@Composable () -> Unit)?,
    containerColor: Color,
    containerShape: Shape,
    itemShape: Shape,
    itemHighlightColor: Color,
    itemContent: @Composable (T, Boolean) -> Unit,
    onItemClick: (T) -> Unit,
) {
    val transition = updateTransition(visibleState, label = "ExpandPicker")
    // 容器整体：按展开方向自锚点一侧缩放并淡入/淡出
    val containerScale by transition.animateFloat(
        transitionSpec = { tween(CONTAINER_SCALE_MS) },
        label = "containerScale",
    ) { expanded -> if (expanded) 1f else CONTAINER_CLOSED_SCALE }
    val containerAlpha by transition.animateFloat(
        transitionSpec = { tween(CONTAINER_FADE_MS) },
        label = "containerAlpha",
    ) { expanded -> if (expanded) 1f else 0f }
    // 缩放原点落在触发区域一侧，使菜单自触发处向外展开
    val transformOrigin = when (direction) {
        ExpandDirection.Up -> TransformOrigin(0.5f, 1f)
        ExpandDirection.Down -> TransformOrigin(0.5f, 0f)
        ExpandDirection.Right -> TransformOrigin(0f, 0.5f)
    }

    // header 占第一个呈现位，其后选项顺延
    val headerCount = if (header != null) 1 else 0

    Surface(
        modifier = Modifier.graphicsLayer {
            scaleX = containerScale
            scaleY = containerScale
            alpha = containerAlpha
            this.transformOrigin = transformOrigin
        },
        shape = containerShape,
        color = containerColor,
        shadowElevation = MENU_SHADOW_ELEVATION,
    ) {
        Column(
            modifier = Modifier
                .padding(MENU_PADDING)
                // 以最宽选项为公共宽度：让各选项勾选位右对齐到同一竖直边，而非随文字长度错落
                .width(IntrinsicSize.Max),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (header != null) {
                ExpandMenuRow(transition = transition, staggerIndex = 0) {
                    header()
                }
            }
            options.forEachIndexed { index, option ->
                val isSelected = option == selected
                ExpandMenuRow(
                    transition = transition,
                    staggerIndex = index + headerCount,
                    shape = itemShape,
                    background = if (isSelected) itemHighlightColor else Color.Transparent,
                    onClick = { onItemClick(option) },
                ) {
                    itemContent(option, isSelected)
                }
            }
        }
    }
}

// 单个选项行：按呈现位错峰淡入/淡出，始终参与布局（仅透明度变化），
// 避免因显隐进出组合树导致容器尺寸逐帧变化
@Composable
private fun ExpandMenuRow(
    transition: Transition<Boolean>,
    staggerIndex: Int,
    modifier: Modifier = Modifier,
    shape: Shape? = null,
    background: Color = Color.Transparent,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val itemAlpha by transition.animateFloat(
        transitionSpec = { tween(ITEM_ANIM_MS, delayMillis = staggerIndex * ITEM_STAGGER_MS) },
        label = "itemAlpha$staggerIndex",
    ) { expanded -> if (expanded) 1f else 0f }
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

// 展开式菜单的定位：按方向贴触发区域对应侧，空间不足时翻转到对侧；水平/垂直按传入对齐方式落位，
// 并按需夹在锚定窗口内，避免贴边触发时菜单溢出屏幕
@Composable
private fun expandPositionProvider(
    direction: ExpandDirection,
    horizontalAlignment: Alignment.Horizontal,
    verticalAlignment: Alignment.Vertical,
    clampToWindow: Boolean,
): PopupPositionProvider {
    val density = LocalDensity.current
    return remember(density, direction, horizontalAlignment, verticalAlignment, clampToWindow) {
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
                    if (clampToWindow) {
                        rawX.coerceIn(marginPx, (windowSize.width - width - marginPx).coerceAtLeast(marginPx))
                    } else rawX
                }
                val clampY = { rawY: Int ->
                    if (clampToWindow) {
                        rawY.coerceIn(marginPx, (windowSize.height - height - marginPx).coerceAtLeast(marginPx))
                    } else rawY
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
                        y = if (anchorBounds.top - height - gapPx >= 0) anchorBounds.top - height - gapPx
                        else anchorBounds.bottom + gapPx
                        x = horizontalX
                    }
                    ExpandDirection.Down -> {
                        y = if (anchorBounds.bottom + height + gapPx <= windowSize.height || !clampToWindow) {
                            anchorBounds.bottom + gapPx
                        } else {
                            anchorBounds.top - height - gapPx
                        }
                        x = horizontalX
                    }
                    ExpandDirection.Right -> {
                        x = if (anchorBounds.right + width + gapPx <= windowSize.width || !clampToWindow) {
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

/**
 * 触发区域滑动手势判定：主轴（与展开方向一致）主导且累计位移越过 [thresholdPx] 时按方向触发。
 *
 * 越过触摸阈值前不消费任何事件：主轴上的反向让给上层手势（纵向反向让给切歌、横向反向让给翻页），
 * 判为主轴即接管本次手势（消费位移与抬手），上层手势据此让出，一次滑动不会触发两个动作。
 * 未构成滑动的手势不被消费，点击照常由 [combinedClickable] 处理。
 */
private suspend fun PointerInputScope.detectSwipe(
    thresholdPx: Float,
    horizontal: Boolean,
    expandPositive: Boolean,
    onSwipeExpand: () -> Unit,
    onSwipeCollapse: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var accX = 0f
        var accY = 0f
        // 是否已判定为主轴滑动并接管本次手势
        var claimed = false
        // 是否已触发：一次手势只触发一次
        var fired = false
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (change.isConsumed) break
            accX += change.positionChange().x
            accY += change.positionChange().y
            val mainAcc = if (horizontal) accX else accY
            val crossAcc = if (horizontal) accY else accX
            if (!claimed) {
                val slop = viewConfiguration.touchSlop
                if (abs(mainAcc) >= slop || abs(crossAcc) >= slop) {
                    // 仅在主轴主导时接管，其余方向原样放行
                    if (abs(mainAcc) > abs(crossAcc)) claimed = true else break
                }
            }
            if (claimed) {
                change.consume()
                if (!fired) {
                    val exceedExpand = if (expandPositive) mainAcc >= thresholdPx else mainAcc <= -thresholdPx
                    val exceedCollapse = if (expandPositive) mainAcc <= -thresholdPx else mainAcc >= thresholdPx
                    when {
                        exceedExpand -> {
                            fired = true
                            onSwipeExpand()
                        }
                        exceedCollapse -> {
                            fired = true
                            onSwipeCollapse()
                        }
                    }
                }
            }
            if (!change.pressed) break
        }
    }
}

// 相邻两项呈现/隐藏的错峰间隔
private const val ITEM_STAGGER_MS = 40
// 单项淡入/淡出时长
private const val ITEM_ANIM_MS = 140
// 容器整体缩放时长
private const val CONTAINER_SCALE_MS = 180
// 容器整体淡入/淡出时长
private const val CONTAINER_FADE_MS = 120
// 收起态容器缩放：自锚点一侧略小，展开时放大到 1
private const val CONTAINER_CLOSED_SCALE = 0.9f
// 菜单容器阴影
private val MENU_SHADOW_ELEVATION = 8.dp
// 菜单容器内边距
private val MENU_PADDING = 4.dp
// 菜单与触发区域的间隔
private val MENU_GAP_DP = 4.dp
// 菜单距屏幕边缘的最小边距
private val HORIZONTAL_MARGIN_DP = 8.dp
// 触发区域沿展开方向滑动唤起菜单所需的最小位移：方向判定已由系统触摸阈值把关，此处取两倍量级排除抖动
private val SWIPE_TRIGGER_DISTANCE = 32.dp