package com.yichao.evilgodxu.ui.gesture

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs

// 横滑切层阈值 72dp
private val SwipeDistance = 72.dp

/**
 * 右滑返回：拖过阈值即回退，纵向与逆向不接管。
 * enabled 在手势发生时读取，pass 决定判定阶段。
 */
@Composable
fun Modifier.swipeBack(
    enabled: () -> Boolean = { true },
    pass: PointerEventPass = PointerEventPass.Main,
    onBack: () -> Unit,
): Modifier = swipeHorizontal(direction = 1, enabled = enabled, pass = pass, onSwipe = onBack)

/**
 * 左滑前进，与 swipeBack 镜像
 */
@Composable
fun Modifier.swipeForward(
    enabled: () -> Boolean = { true },
    pass: PointerEventPass = PointerEventPass.Main,
    onForward: () -> Unit,
): Modifier = swipeHorizontal(direction = -1, enabled = enabled, pass = pass, onSwipe = onForward)

/** direction 1 向右，-1 向左 */
@Composable
private fun Modifier.swipeHorizontal(
    direction: Int,
    enabled: () -> Boolean,
    pass: PointerEventPass,
    onSwipe: () -> Unit,
): Modifier {
    val currentOnSwipe by rememberUpdatedState(onSwipe)
    val currentEnabled by rememberUpdatedState(enabled)
    val thresholdPx = with(LocalDensity.current) { SwipeDistance.toPx() }
    return then(
        remember(thresholdPx, direction) {
            Modifier.pointerInput(thresholdPx, direction) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = pass)
                    if (!currentEnabled()) return@awaitEachGesture
                    // 方向锁定：任一轴越阈值后判定横向主导
                    var accX = 0f
                    var accY = 0f
                    var locked = false
                    var horizontal = false
                    while (!locked) {
                        val event = awaitPointerEvent(pass)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed || change.isConsumed) break
                        accX += change.positionChange().x
                        accY += change.positionChange().y
                        if (abs(accX) >= slop || abs(accY) >= slop) {
                            horizontal = abs(accX) > abs(accY)
                            locked = true
                            // 锁定帧一并消费，避免子级当作起点
                            if (horizontal && accX * direction > 0f) change.consume()
                        }
                    }
                    // 纵向主导或逆向拖都不切层
                    if (!locked || !horizontal || accX * direction <= 0f) return@awaitEachGesture
                    var swipeX = accX
                    while (true) {
                        val event = awaitPointerEvent(pass)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed || change.isConsumed) break
                        swipeX += change.positionChange().x
                        change.consume()
                    }
                    if (swipeX * direction >= thresholdPx) currentOnSwipe()
                }
            }
        }
    )
}
