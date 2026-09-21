package com.yichao.evilgodxu.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

// 跑马灯滚动速度：长文本按此速度匀速平移
private val MARQUEE_SPEED = 24.dp

// 跑马灯两端停顿时长：滚动前/后短暂停留便于阅读
private const val MARQUEE_EDGE_PAUSE_MS = 1200L

/**
 * 单行文本跑马灯：宽度超出容器时来回滚动，未溢出时静态左对齐。
 *
 * 采用往返滚动而非单向循环：滚到末尾原路退回，两端各停一拍，长文本可以完整读完而不用重看开头。
 * 滚动不消费手势，放在可拖拽容器（如横向轮播）内不会与容器自身的滑动抢手势。
 */
@Composable
internal fun MarqueeText(
    text: String,
    color: Color,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val layout = remember(text, style) { textMeasurer.measure(AnnotatedString(text), style) }
    var containerWidthPx by remember { mutableFloatStateOf(0f) }
    val maxScroll = (layout.size.width - containerWidthPx).coerceAtLeast(0f)
    val offset = remember(text) { Animatable(0f) }
    LaunchedEffect(text, maxScroll) {
        if (containerWidthPx <= 0f || maxScroll <= 0f) {
            offset.snapTo(0f)
            return@LaunchedEffect
        }
        // 按固定速度折算滚动时长，保证不同长度标题速度一致
        val speedPxPerMs = with(density) { MARQUEE_SPEED.toPx() } / 1000f
        val scrollMs = (maxScroll / speedPxPerMs).toInt().coerceAtLeast(1)
        while (isActive) {
            delay(MARQUEE_EDGE_PAUSE_MS)
            offset.animateTo(maxScroll, tween(scrollMs, easing = LinearEasing))
            delay(MARQUEE_EDGE_PAUSE_MS)
            offset.animateTo(0f, tween(scrollMs, easing = LinearEasing))
        }
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(with(density) { layout.size.height.toDp() })
            .onSizeChanged { containerWidthPx = it.width.toFloat() }
            .drawWithContent {
                if (layout.size.width <= 0f || size.width <= 0f) return@drawWithContent
                val translateX = if (layout.size.width <= size.width) 0f else -offset.value
                clipRect(left = 0f, top = 0f, right = size.width, bottom = size.height) {
                    drawText(layout, color = color, topLeft = Offset(translateX, 0f))
                }
            }
            .semantics { contentDescription = text }
    )
}
