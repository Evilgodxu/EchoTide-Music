package com.yichao.evilgodxu.ui.component

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// 搜索框遮住的底缘渐隐带，框体高度内全隐
private val SEARCH_BAR_HIDDEN_DP = SEARCH_BAR_REGION_DP

// 弹层过渡带 40dp，压在操作按钮区
internal val SEARCH_BAR_FADE_FEATHER_DP = 40.dp

// 歌单页过渡带 8dp，压进末行留白
internal val SEARCH_BAR_LIST_FADE_FEATHER_DP = 8.dp

// 弹层底部留白为框体加过渡带
internal val SEARCH_BAR_EDGE_FADE_DP = SEARCH_BAR_HIDDEN_DP + SEARCH_BAR_FADE_FEATHER_DP

/**
 * 列表底缘渐隐：框体高度内全隐，再向上淡出。
 * 用 DstIn 蒙层保留内容，避免固定底色留下色带。
 */
internal fun Modifier.fadeUnderBottomSearchBar(feather: Dp = SEARCH_BAR_FADE_FEATHER_DP): Modifier = this
    .graphicsLayer {
        // 固定离屏层，供 DstIn 合成
        compositingStrategy = CompositingStrategy.Offscreen
    }
    .drawWithContent {
        drawContent()
        val hiddenPx = SEARCH_BAR_HIDDEN_DP.toPx()
        val bandPx = hiddenPx + feather.toPx()
        // 内容比遮罩带还矮时不渐隐
        if (size.height <= bandPx) return@drawWithContent
        // DstIn 按蒙层透明度保留内容，RGB 不参与
        drawRect(
            brush = coverFadeBrush(
                color = Color.Black,
                start = 1f - bandPx / size.height,
                end = 1f - hiddenPx / size.height,
            ),
            blendMode = BlendMode.DstIn,
        )
    }
