package com.yichao.evilgodxu.ui.component

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.core.view.drawToBitmap
import com.yichao.evilgodxu.log.CrashLogManager

// 揭示时长：圆从圆心铺满整幅画布的用时。铺满全幅的位移量远大于元素级过渡，
// 按常规时长（200–300ms）推进会读不出「从某点展开」的先后，故取 1500ms
private const val SCREEN_REVEAL_MS = 1500

private const val TAG = "ScreenReveal"

/**
 * 整屏揭示：以某一圆心展开新画面，圆外仍留旧画面，换画面因此有可辨的先后次序。
 *
 * 由触发方在画面仍是旧内容时发起——已知圆心用 [revealAt]（主题模式切换的点击发生在对话框弹出之前，
 * 位置只能由入口带过来），圆心即按下位置时用 [revealFromPress]（选曲播放点在列表行上，位置由宿主统一观察）。
 * 宿主随后取一份整屏快照，按圆形差集铺在内容之上。
 * 快照必须在内容变更前取：变更后再取到的已是新画面，就没有可对照的旧画面。
 */
@Stable
class ScreenRevealController {
    internal var request: ((Offset?) -> Unit)? = null

    fun revealAt(origin: Offset) {
        request?.invoke(origin)
    }

    /**
     * 以宿主记录的最近一次按下位置为圆心。
     *
     * 位置由宿主统一观察，各列表行不必各自换算到宿主坐标系。宿主未记录到按下位置时
     * （如外部起播这类没有按键的触发路径）回退左上角。
     */
    fun revealFromPress() {
        request?.invoke(null)
    }
}

/**
 * 整屏揭示宿主：内容之上按圆形差集绘制旧画面快照——圆内露出新内容，圆外仍是旧画面。
 * 圆心与半径都在本宿主的坐标系内，[ScreenRevealController.revealAt] 传入的圆心须与之一致；
 * 按下位置由本宿主就地观察，与绘制同处一个坐标系，故 [ScreenRevealController.revealFromPress] 无需换算。
 */
@Composable
fun ScreenRevealHost(
    controller: ScreenRevealController,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var snapshot by remember { mutableStateOf<Bitmap?>(null) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var pressOrigin by remember { mutableStateOf<Offset?>(null) }
    val progress = remember { Animatable(1f) }
    val view = LocalView.current

    controller.request = { requested ->
        // 视图尚未完成首次布局时无从取图，跳过本次：揭示是锦上添花，不因此中断内容变更。
        // 快照以软件画布重绘整棵视图，层级里一旦混入硬件位图就会抛异常（显示端已收口为软件位图，
        // 见 MusicCoverLoader.toSoftware），此处仍兜住：快照失败只是没有揭示，绝不能连累内容变更
        if (view.width > 0 && view.height > 0) {
            val drawn = runCatching { view.drawToBitmap() }
                .onFailure { CrashLogManager.logException(TAG, "整屏揭示取快照失败", it) }
                .getOrNull()
            if (drawn != null) {
                snapshot = drawn
                origin = requested ?: pressOrigin ?: Offset.Zero
                // 按下位置取用后即清：无按下的后续请求不该沿用上一次交互的陈旧位置
                if (requested == null) pressOrigin = null
            }
        }
    }
    LaunchedEffect(snapshot) {
        if (snapshot == null) return@LaunchedEffect
        progress.snapTo(0f)
        progress.animateTo(1f, tween(SCREEN_REVEAL_MS))
        snapshot = null
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            // 只在 Initial 阶段观察、不消费事件：按下位置仅用于揭示圆心，子级手势不受影响
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        pressOrigin = awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Initial,
                        ).position
                    }
                }
            }
            .drawWithContent {
                drawContent()
                val bitmap = snapshot ?: return@drawWithContent
                drawOldScreenOutsideReveal(bitmap, origin, progress.value)
            },
    ) {
        content()
    }
}

// 旧画面按圆形差集绘制：只画圆外部分，圆内交给下方已就位的新内容。
// 圆随进度以 [origin] 为圆心向外扩张，圆心到四角的最远距离即铺满整幅的终态半径
private fun DrawScope.drawOldScreenOutsideReveal(bitmap: Bitmap, origin: Offset, progress: Float) {
    val radius = maxRevealRadius(origin, size.width, size.height) * progress
    val path = Path().apply {
        addOval(
            Rect(
                left = origin.x - radius,
                top = origin.y - radius,
                right = origin.x + radius,
                bottom = origin.y + radius,
            ),
        )
    }
    clipPath(path, ClipOp.Difference) {
        drawImage(bitmap.asImageBitmap())
    }
}

// 圆心到画布四角的最远距离：半径取到该值时圆至少覆盖整幅画布
private fun maxRevealRadius(origin: Offset, width: Float, height: Float): Float = maxOf(
    origin.getDistance(),
    Offset(width, 0f).minus(origin).getDistance(),
    Offset(0f, height).minus(origin).getDistance(),
    Offset(width, height).minus(origin).getDistance(),
)
