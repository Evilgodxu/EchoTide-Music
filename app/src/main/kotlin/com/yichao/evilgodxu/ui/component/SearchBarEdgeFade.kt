package com.yichao.evilgodxu.ui.component

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

// 悬浮搜索框遮住的列表底缘渐隐带：底部一段按框体高度完全隐去，再向上留一段平滑过渡。
// 搜索框本身只有描边、没有底色，列表内容若直接穿进框内，文字与描边会糊在一起；
// 渐隐后曲目看起来是收进了框后，而不是被一条直线切断。
private val SEARCH_BAR_HIDDEN_DP = SEARCH_BAR_REGION_DP

// 过渡带高度：约一行曲目的高度，让框体上方正好一整行完成淡出，再多会显得列表整体发虚
private val SEARCH_BAR_FADE_FEATHER_DP = 40.dp

// 列表底部留白高度：等于整条渐隐带。末项停在这条带上方才不会被渐隐抹掉，也不会被框体压住
internal val SEARCH_BAR_EDGE_FADE_DP = SEARCH_BAR_HIDDEN_DP + SEARCH_BAR_FADE_FEATHER_DP

/**
 * 常驻的列表底缘渐隐：把列表内容在 [SEARCH_BAR_HIDDEN_DP] 内整段隐去，再向上 [SEARCH_BAR_FADE_FEATHER_DP]
 * 平滑淡出。
 *
 * 不跟滚动状态联动，也不做显隐动画：搜索框常驻在列表底部，被隐去的这一段始终由框体盖着；
 * 起滑、停滑、触底都没有会变的量，底部不会出现整段明暗跳变。调用方须按 [SEARCH_BAR_EDGE_FADE_DP]
 * 给列表留出底部空白，末项才能停在渐隐带上方。
 *
 * DstIn 蒙层直接按透明度保留列表内容，而不是叠一层底色渐变：播放列表弹层底色随主题，
 * 歌单页背后是首页沉浸渐变，任何固定颜色的渐变都会在内容底部留下一条色带
 * （与搜索历史标签的横向渐隐、迷你播放器列表的顶边渐隐同一做法）。
 */
internal fun Modifier.fadeUnderBottomSearchBar(): Modifier = this
    .graphicsLayer {
        // 固定离屏层：蒙层要靠 DstIn 合成离屏层上的列表内容；
        // 若随状态在 Offscreen/Auto 间切换，图层会在切换的那一帧重建
        compositingStrategy = CompositingStrategy.Offscreen
    }
    .drawWithContent {
        drawContent()
        val hiddenPx = SEARCH_BAR_HIDDEN_DP.toPx()
        val bandPx = hiddenPx + SEARCH_BAR_FADE_FEATHER_DP.toPx()
        // 列表被压得比遮罩带还矮（键盘展开且窗口极矮）时不做渐隐，否则整列内容都会被抹掉
        if (size.height <= bandPx) return@drawWithContent
        // DstIn 取蒙层的透明度保留内容：实色段（带上方）原样可见，渐隐段向下淡到全透明，
        // 黑色只为占位，蒙层的 RGB 不参与合成
        drawRect(
            brush = coverFadeBrush(
                color = Color.Black,
                start = 1f - bandPx / size.height,
                end = 1f - hiddenPx / size.height,
            ),
            blendMode = BlendMode.DstIn,
        )
    }
