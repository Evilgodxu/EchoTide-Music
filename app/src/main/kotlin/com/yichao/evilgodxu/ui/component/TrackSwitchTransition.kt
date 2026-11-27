package com.yichao.evilgodxu.ui.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.playback.TrackSwitchKind

// 横移 600ms，入场退场同长同速，两层拼满容器
internal const val TRACK_SLIDE_MS = 600

// 交叠淡入 320ms、淡出 220ms
private const val TRACK_FADE_ENTER_MS = 320
private const val TRACK_FADE_EXIT_MS = 220

// 元素级切歌过渡方式
internal enum class TrackSwitchStyle {
    // 横移：两层拼满容器，亮度不塌陷
    Slide,

    // 交叠淡入淡出，用于固定小格子的迷你播放器
    Crossfade,
}

/**
 * 过渡锚点：曲目实例与变更类型；过渡按 id 判定
 */
@Immutable
internal data class TrackSwitchAnchor(
    val track: MusicTrack?,
    val kind: TrackSwitchKind,
) {
    val id: Long? get() = track?.id

    val title: String get() = track?.title.orEmpty()

    val artist: String get() = track?.artist.orEmpty()
}

/** 由曲目与变更类型构造锚点 */
@Composable
internal fun rememberTrackSwitchAnchor(track: MusicTrack?, kind: TrackSwitchKind): TrackSwitchAnchor =
    remember(track, kind) { TrackSwitchAnchor(track = track, kind = kind) }

/**
 * 切歌过渡：新旧内容交叠，方式由 style 决定。
 * 横移方向取自变更类型，无方向者直接替换。
 * 回前台不补播，以可见会话为键重建。
 */
@Composable
internal fun TrackSwitchTransition(
    anchor: TrackSwitchAnchor,
    modifier: Modifier = Modifier,
    style: TrackSwitchStyle = TrackSwitchStyle.Slide,
    content: @Composable (TrackSwitchAnchor) -> Unit,
) {
    // 入场方向取自本次锚点的类型
    val kind = anchor.kind
    val visibleSession = rememberVisibleSession()
    key(visibleSession) {
        AnimatedContent(
            targetState = anchor,
            modifier = modifier,
            // 以曲目标识为键，元数据更新不误触发
            contentKey = { it.id },
            transitionSpec = {
                when (style) {
                    TrackSwitchStyle.Crossfade ->
                        fadeIn(tween(TRACK_FADE_ENTER_MS)) togetherWith fadeOut(tween(TRACK_FADE_EXIT_MS))

                    TrackSwitchStyle.Slide -> when (kind) {
                        TrackSwitchKind.Previous -> trackSlideTransform(enterFromLeft = true)
                        TrackSwitchKind.Next -> trackSlideTransform(enterFromLeft = false)
                        // 无方向可读，直接替换
                        TrackSwitchKind.Select, TrackSwitchKind.SameContent -> noElementTransition
                    }
                }
            },
            label = "trackSwitch",
            content = { state -> content(state) },
        )
    }
}

/** 不做元素级过渡，直接替换 */
internal val noElementTransition: ContentTransform =
    EnterTransition.None togetherWith ExitTransition.None

/**
 * 整幅横移：新内容自一侧移入，旧内容等速移出
 */
internal fun trackSlideTransform(enterFromLeft: Boolean): ContentTransform {
    val enter: (Int) -> Int = if (enterFromLeft) { width -> -width } else { width -> width }
    val exit: (Int) -> Int = if (enterFromLeft) { width -> width } else { width -> -width }
    // 默认减速曲线，落位不生硬
    val spec = tween<IntOffset>(TRACK_SLIDE_MS)
    return slideInHorizontally(spec, enter)
        .togetherWith(slideOutHorizontally(spec, exit))
}
