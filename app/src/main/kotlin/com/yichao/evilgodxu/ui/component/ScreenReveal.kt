package com.yichao.evilgodxu.ui.component

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.platform.LocalView
import androidx.core.view.drawToBitmap
import com.yichao.evilgodxu.log.CrashLogManager

// 揭示时长 1500ms，整幅位移需可辨先后
private const val SCREEN_REVEAL_MS = 1500

private const val TAG = "ScreenReveal"

/**
 * 整屏揭示：以圆心展开新画面，圆外留旧快照
 */
@Stable
class ScreenRevealController {
    internal var request: ((Offset) -> Unit)? = null

    fun revealAt(origin: Offset) {
        request?.invoke(origin)
    }
}

/**
 * 揭示宿主：圆内新内容，圆外旧快照
 */
@Composable
fun ScreenRevealHost(
    controller: ScreenRevealController,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var snapshot by remember { mutableStateOf<Bitmap?>(null) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val progress = remember { Animatable(1f) }
    val view = LocalView.current

    controller.request = { requested ->
        // 未布局或取图失败则跳过，不影响内容变更
        if (view.width > 0 && view.height > 0) {
            val drawn = runCatching { view.drawToBitmap() }
                .onFailure { CrashLogManager.logException(TAG, "整屏揭示取快照失败", it) }
                .getOrNull()
            if (drawn != null) {
                snapshot = drawn
                origin = requested
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
            .drawWithContent {
                drawContent()
                val bitmap = snapshot ?: return@drawWithContent
                drawOldScreenOutsideReveal(bitmap, origin, progress.value)
            },
    ) {
        content()
    }
}

// 只画圆外旧画面，圆内交给新内容
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

// 圆心到四角最远距离，即铺满半径
private fun maxRevealRadius(origin: Offset, width: Float, height: Float): Float = maxOf(
    origin.getDistance(),
    Offset(width, 0f).minus(origin).getDistance(),
    Offset(0f, height).minus(origin).getDistance(),
    Offset(width, height).minus(origin).getDistance(),
)
