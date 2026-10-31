package com.yichao.evilgodxu.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
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

// 渐隐强度与搜索框显隐同速：框体的淡入/滑入时长为 160ms（见 BottomSearchBarOverlay）
private const val SEARCH_BAR_FADE_DURATION_MS = 160

/**
 * 列表底缘渐隐：搜索框可见时把列表内容按 [SEARCH_BAR_HIDDEN_DP] + [SEARCH_BAR_FADE_FEATHER_DP]
 * 的高度渐隐到底，搜索框隐藏（滚动中、已滚到底）时同步撤掉，末项不会被凭空抹淡。
 *
 * DstIn 蒙层直接按透明度保留列表内容，而不是叠一层底色渐变：播放列表弹层底色随主题，
 * 歌单页背后是首页沉浸渐变，任何固定颜色的渐变都会在内容底部留下一条色带
 * （与搜索历史标签的横向渐隐、迷你播放器列表的顶边渐隐同一做法）。
 */
@Composable
internal fun Modifier.fadeUnderBottomSearchBar(visible: Boolean): Modifier {
    val fade = animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(SEARCH_BAR_FADE_DURATION_MS),
        label = "search_bar_edge_fade",
    )
    return this
        .graphicsLayer {
            // 只在遮罩有内容可隐时才走离屏层：列表静止时不重绘，滚动期间强度已归零，
            // 常态下不必为这张全屏图层付离屏合成的代价
            compositingStrategy =
                if (fade.value > 0f) CompositingStrategy.Offscreen else CompositingStrategy.Auto
        }
        .drawWithContent {
            drawContent()
            val strength = fade.value
            if (strength <= 0f) return@drawWithContent
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
                alpha = strength,
                blendMode = BlendMode.DstIn,
            )
        }
}
