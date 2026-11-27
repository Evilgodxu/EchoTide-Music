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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import com.yichao.evilgodxu.ui.component.MarqueeText

// 单行文本：溢出时往复跑马灯 + 首尾渐隐
@Composable
internal fun MarqueeInfoLine(
    text: String,
    fontSize: TextUnit,
    fontWeight: FontWeight,
    color: Color,
    modifier: Modifier = Modifier,
    // 未溢出时的对齐；溢出后内容铺满，对齐无差异
    textAlign: TextAlign = TextAlign.Center,
) {
    val textMeasurer = rememberTextMeasurer()
    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = if (textAlign == TextAlign.Start) Alignment.CenterStart else Alignment.Center,
    ) {
        // 先合并 LocalTextStyle 再测量，分支判定才一致
        val textStyle = LocalTextStyle.current.merge(
            TextStyle(fontSize = fontSize, fontWeight = fontWeight),
        )
        val textWidthPx = textMeasurer.measure(AnnotatedString(text), textStyle).size.width
        val containerWidthPx = with(LocalDensity.current) { maxWidth.toPx() }
        if (containerWidthPx > 0f && textWidthPx > containerWidthPx) {
            // 溢出交给跑马灯，两端各停一拍，再加渐隐
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
                textAlign = textAlign,
            )
        }
    }
}

// 渐隐宽度 40dp，与最近搜索标签一致
private val MarqueeFadeWidth = 40.dp

// 首尾渐隐：DstOut，不铺底色
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
