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

// 横滑切层的判定距离：横向拖过该距离松手即切一层
private val SwipeDistance = 72.dp

/**
 * 右滑返回：向右拖过阈值即回退一级，向左拖不动。
 *
 * 起手落在空白处或普通行内都算，落在自身消费横向位移的控件（开关、滑杆、分页）上则不接管；
 * 纵向主导时让出，列表照常滚动——一层滑动只对应一个去向。
 *
 * @param enabled 按需读而非由组合期传值：启用条件可能含实时状态（如分页是否正在滑动），
 *   读取须落在手势发生时
 * @param pass 判定所据的事件阶段。内容自身没有横向滑动时 Main 即可——此时子级已先看过事件，
 *   本手势只收无人认领的位移；子级占着横向滑动时须在 Initial 阶段抢先判定并消费，
 *   否则这段位移会被子级吃下，本手势再收到时就已是「已消费」
 */
@Composable
fun Modifier.swipeBack(
    enabled: () -> Boolean = { true },
    pass: PointerEventPass = PointerEventPass.Main,
    onBack: () -> Unit,
): Modifier = swipeHorizontal(direction = 1, enabled = enabled, pass = pass, onSwipe = onBack)

/**
 * 左滑前进：向左拖过阈值即进入下一层（如歌单页总览继续左滑进入设置页），向右拖不动。
 *
 * 与 [swipeBack] 互为镜像：起手落点、纵向让位与消费规则完全一致。两者通常挂在同一容器上，
 * 用互斥的 [enabled] 条件区分当前层级能往哪边走。
 */
@Composable
fun Modifier.swipeForward(
    enabled: () -> Boolean = { true },
    pass: PointerEventPass = PointerEventPass.Main,
    onForward: () -> Unit,
): Modifier = swipeHorizontal(direction = -1, enabled = enabled, pass = pass, onSwipe = onForward)

/** @param direction 1 为向右切层，-1 为向左切层 */
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
                    // 方向锁定：累计位移直到任一轴越过触摸阈值，横向主导才由本手势接管
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
                            // 锁定这一帧即一并消费：Initial 阶段下子级尚未看过它，
                            // 留给子级会被当作它自己的滑动起点
                            if (horizontal && accX * direction > 0f) change.consume()
                        }
                    }
                    // 纵向主导让给列表滚动；逆向拖不切层
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
