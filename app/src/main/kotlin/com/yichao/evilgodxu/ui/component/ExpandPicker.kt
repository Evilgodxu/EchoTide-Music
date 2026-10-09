package com.yichao.evilgodxu.ui.component

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.clipRect
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
 * 展开式选择器：点击、沿展开方向滑动展开，反向滑动收起，选中选项或点击其他区域自动收回。
 *
 * 触发区域由 [trigger] 槽位承载，其大小决定触控热区；展开后菜单自触发一侧向外长出，
 * 选项自锚点侧起逐项浮现，收起时逐项隐去。选项内容由 [itemContent] 注入，选中态（背景高亮）
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
                    onCollapse = { expanded = false },
                )
            }
        }
    }
}

/**
 * 菜单主体：容器自锚点一侧向外裁切展开/收起，内部选项按「距锚点远近」逐项浮现/隐去。
 *
 * 容器与选项共用同一条进度时间轴（[progress]，0 收起 / 1 展开）：容器的可见区域随进度自锚点一侧长出，
 * 选项的浮现窗口也映射在同一进度轴上——容器展开到该项所在位置时它开始浮现，收起时随进度回落逐项隐去，
 * 二者因此天然同步，不会出现等容器完全展开后内容才弹出、或容器收完内容还残留的脱节。
 *
 * 展开由裁切而非整体缩放完成：缩放只是把整块内容缩小，视觉上像「瞬间出现后再微调」；裁切则让容器
 * 真正从锚点一侧往外生长，边缘推到哪一项，哪一项就露出来。裁切在绘制层进行，容器布局尺寸在动画期间恒定——
 * 弹窗定位依赖内容尺寸，若改变尺寸会导致定位逐帧重算（抖动/闪烁的来源），且项始终参与组合与布局，
 * 不会在末尾露出空框。展开与收起由 [visibleState] 的过渡驱动：它由动画库创建，首次组合即为
 * current=false、target=true，因此不会出现「首帧已是展开态故不播动画」。
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
    onCollapse: () -> Unit,
) {
    val transition = rememberTransition(visibleState, label = "ExpandPicker")
    // 唯一进度源：容器与选项都从它取值，保证展开/收起过程中二者节奏一致
    val progress by transition.animateFloat(
        transitionSpec = { tween(MENU_REVEAL_MS) },
        label = "revealProgress",
    ) { expanded -> if (expanded) 1f else 0f }
    // 容器不透明度：仅在展开初期快速显形，用于柔化裁切前沿；实际展开由裁切驱动
    val containerAlpha = (progress / CONTAINER_FADE_PROGRESS).coerceIn(0f, 1f)

    // header 占第一个呈现位，其后选项顺延
    val headerCount = if (header != null) 1 else 0
    val itemCount = options.size + headerCount

    Surface(
        modifier = Modifier
            .graphicsLayer { alpha = containerAlpha }
            .drawWithContent {
                // 自锚点一侧向外裁切出可见区域：展开时边缘持续推进，边缘越过的项随之露出；收起时反向收回
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
        shadowElevation = MENU_SHADOW_ELEVATION,
    ) {
        Column(
            modifier = Modifier
                .padding(MENU_PADDING)
                // 以最宽选项为公共宽度：各选项等宽，避免随文字长度错落
                .width(IntrinsicSize.Max),
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

// 呈现位到「距锚点远近」的换算：0 表示最靠近锚点、最先浮现。
// Up 的锚点在下，呈现位自上而下与锚点远近相反，故需倒置；Down / Right 的锚点在正序一侧，保持原序
private fun anchorOrder(direction: ExpandDirection, position: Int, itemCount: Int): Int =
    if (direction == ExpandDirection.Up) itemCount - 1 - position else position

// 由共享进度换算单项透明度：距锚点越近，浮现起点越早（收起时隐去越晚），
// 末项的浮现终点对齐进度 1，与容器完全展开同步
private fun itemRevealAlpha(progress: Float, order: Int, itemCount: Int): Float {
    val firstStart = ITEM_REVEAL_LEAD_IN
    val lastStart = (1f - ITEM_REVEAL_SPAN).coerceAtLeast(firstStart)
    val start = if (itemCount <= 1) firstStart
    else firstStart + (lastStart - firstStart) * order / (itemCount - 1)
    return ((progress - start) / ITEM_REVEAL_SPAN).coerceIn(0f, 1f)
}

// 单个选项行：透明度由共享进度换算而来，始终参与布局（仅透明度变化），
// 避免因显隐进出组合树导致容器尺寸逐帧变化
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

// 容器展开/收起总时长：容器与选项的浮现/隐去共用此时间轴
// 取较长时长让裁切推进与逐项浮现都能看清；过快会显得生硬
private const val MENU_REVEAL_MS = 560
// 容器不透明度到达 1 所需的进度比例：容器先快速显形以柔化裁切前沿，随后裁切继续推进完成展开
private const val CONTAINER_FADE_PROGRESS = 0.3f
// 首项开始浮现前容器的展开进度：先让容器露出，再逐项带出内容
private const val ITEM_REVEAL_LEAD_IN = 0.12f
// 单项浮现占用的进度窗口：末项据此收束在进度 1，与容器完全展开同步
private const val ITEM_REVEAL_SPAN = 0.3f
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