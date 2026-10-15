package com.yichao.evilgodxu.screens.home.component.player

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import com.yichao.evilgodxu.ui.component.MarqueeText

// 单行信息文本：宽度超出容器时启用往复式跑马灯，并叠加与「最近搜索」标签一致的首尾渐隐
@Composable
internal fun MarqueeInfoLine(
    text: String,
    fontSize: TextUnit,
    fontWeight: FontWeight,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        // 与渲染同源：合并 LocalTextStyle 后再测量，静态分支与跑马灯分支的宽度判定才一致
        val textStyle = LocalTextStyle.current.merge(
            TextStyle(fontSize = fontSize, fontWeight = fontWeight),
        )
        val textWidthPx = textMeasurer.measure(AnnotatedString(text), textStyle).size.width
        val containerWidthPx = with(LocalDensity.current) { maxWidth.toPx() }
        if (containerWidthPx > 0f && textWidthPx > containerWidthPx) {
            // 超出即交给往复式跑马灯：滚到末尾原路退回，两端各停一拍（见 MarqueeText），
            // 再叠加两端渐隐，使文字进出边缘时淡入淡出而非硬切
            MarqueeText(
                text = text,
                color = color,
                style = textStyle,
                modifier = Modifier.marqueeEdgeFade(),
            )
        } else {
            Text(
                text = text,
                color = color,
                style = textStyle,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

// 跑马灯首尾渐隐宽度：取与「最近搜索」标签横滑渐隐相同的值，保证首页整体视觉一致
private val MarqueeFadeWidth = 40.dp

// 首尾渐隐：在滚动内容之上叠一层 DstOut 蒙层，直接抹掉内容自身的透明度。
// 与「最近搜索」标签横滑渐隐同一套做法——Offscreen 层内用 DstOut，不铺固定底色，
// 沉浸渐变背景上不会留下色带；两端常驻渐隐，文字进出边缘时淡入淡出而非硬切
private fun Modifier.marqueeEdgeFade(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val fadePx = MarqueeFadeWidth.toPx()
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(Color.Black, Color.Transparent),
                startX = 0f,
                endX = fadePx,
            ),
            blendMode = BlendMode.DstOut,
        )
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(Color.Transparent, Color.Black),
                startX = size.width - fadePx,
                endX = size.width,
            ),
            blendMode = BlendMode.DstOut,
        )
    }
