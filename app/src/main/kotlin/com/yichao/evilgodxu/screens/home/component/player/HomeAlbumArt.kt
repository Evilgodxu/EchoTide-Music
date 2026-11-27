package com.yichao.evilgodxu.screens.home.component.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.playback.TrackSwitchKind
import com.yichao.evilgodxu.theme.md_theme_dark_background
import com.yichao.evilgodxu.ui.component.COVER_FADE_RATIO
import com.yichao.evilgodxu.ui.component.CoverTransition
import com.yichao.evilgodxu.ui.component.coverFadeBrush
import com.yichao.evilgodxu.ui.component.coverLeftRightFadeBrush
import com.yichao.evilgodxu.ui.component.coverTopBottomFadeBrush
import com.yichao.evilgodxu.ui.component.noElementTransition
import com.yichao.evilgodxu.ui.component.rememberLargeCoverState
import com.yichao.evilgodxu.ui.component.rememberVisibleSession
import com.yichao.evilgodxu.ui.component.trackSlideTransform

// 两级取图：底色+缩略图打底，再解码原图
// 相邻曲目预热，高清就位淡入，换曲不补播
@Composable
internal fun HomeAlbumArt(
    track: MusicTrack?,
    kind: TrackSwitchKind,
    modifier: Modifier = Modifier,
) {
    // 上张已就位封面；正图与底图都缺时沿用，避免近黑黑闪
    var lastShown by remember { mutableStateOf<ImageBitmap?>(null) }
    // 回前台不补播切歌：会话重建让过渡以当前曲目落位
    val visibleSession = rememberVisibleSession()
    key(visibleSession) {
        AnimatedContent(
            targetState = track,
            // 用曲目标识作键，避免元数据更新误触发
            contentKey = { it?.id },
            transitionSpec = {
                when (kind) {
                    TrackSwitchKind.Previous -> trackSlideTransform(enterFromLeft = true)
                    TrackSwitchKind.Next -> trackSlideTransform(enterFromLeft = false)
                    // 选曲无方向、同源变更直接替换，横移会错位
                    TrackSwitchKind.Select, TrackSwitchKind.SameContent -> noElementTransition
                }
            },
            // 羽化加在过渡层之外，只对合成结果羽化一次
            modifier = modifier,
            label = "homeCover",
        ) { layer ->
            val state = rememberLargeCoverState(layer)
            // 正图未就位先看本曲底图，都没有才沿用上张
            val shown = state.cover ?: lastShown.takeIf { state.pending && state.base == null }
            // 只在取到图时更新，底图不顶掉已就位
            LaunchedEffect(state.cover) { state.cover?.let { lastShown = it } }
            // 占位色与背景同源，取不到退回深色主题色
            val placeholderColor = state.baseColor ?: md_theme_dark_background
            CoverTransition(
                bitmap = shown,
                baseBitmap = state.base,
                contentDescription = layer?.title,
                placeholderColor = placeholderColor,
                // 已有背景色与缩略图，无需音符兜底
                placeholderIconSize = null,
            )
        }
    }
}

// 沉浸封面：全宽置顶，仅下缘渐隐融入背景
@Composable
internal fun HomeImmersiveCover(
    track: MusicTrack?,
    kind: TrackSwitchKind,
    modifier: Modifier = Modifier,
) {
    HomeAlbumArt(
        track = track,
        kind = kind,
        modifier = modifier.bottomFadeMask(),
    )
}

// 横屏封面：四边渐隐，与背景无缝衔接
@Composable
internal fun HomeBlendedCover(
    track: MusicTrack?,
    kind: TrackSwitchKind,
    modifier: Modifier = Modifier,
) {
    HomeAlbumArt(
        track = track,
        kind = kind,
        modifier = modifier.edgeFeatherMask(),
    )
}

// 四边羽化：横纵两次 DstIn 相乘，四角衰减更强
// saveLayer 限定蒙层在节点内，越界一并裁掉
private fun Modifier.edgeFeatherMask(): Modifier = drawWithCache {
    val horizontal = coverLeftRightFadeBrush(Color.Black)
    val vertical = coverTopBottomFadeBrush(Color.Black)
    onDrawWithContent {
        drawIntoCanvas { canvas -> canvas.saveLayer(Rect(Offset.Zero, size), Paint()) }
        drawContent()
        drawRect(brush = horizontal, size = size, blendMode = BlendMode.DstIn)
        drawRect(brush = vertical, size = size, blendMode = BlendMode.DstIn)
        drawIntoCanvas { canvas -> canvas.restore() }
    }
}

// 下缘渐隐：DstIn 蒙层覆盖固定比例
private fun Modifier.bottomFadeMask(): Modifier = drawWithCache {
    val brush = coverFadeBrush(
        // DstIn 只取透明度，实色段用黑色即可
        color = Color.Black,
        start = 1f - COVER_FADE_RATIO,
        end = 1f,
    )
    onDrawWithContent {
        drawIntoCanvas { canvas -> canvas.saveLayer(Rect(Offset.Zero, size), Paint()) }
        drawContent()
        drawRect(brush = brush, size = size, blendMode = BlendMode.DstIn)
        drawIntoCanvas { canvas -> canvas.restore() }
    }
}
