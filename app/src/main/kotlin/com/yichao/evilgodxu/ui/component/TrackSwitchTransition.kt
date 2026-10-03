package com.yichao.evilgodxu.ui.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.yichao.evilgodxu.data.music.model.MusicTrack
import kotlin.math.roundToInt

// 切歌过渡时长：入场略长于退场，新内容先立住，旧内容在其后收干净
private const val TRACK_SWITCH_ENTER_MS = 320
private const val TRACK_SWITCH_EXIT_MS = 220

// 横移幅度（占容器宽度的比例）：只作方向提示，不做整屏位移
private const val TRACK_SWITCH_SLIDE_FRACTION = 0.12f

/**
 * 切歌过渡锚点：只承载「显示身份」与「当帧显示文本」。
 *
 * 以本对象而非整个 [MusicTrack] 作过渡键：[MusicTrack] 在歌词补全、收藏状态等元数据变化时会被整体替换，
 * 直接用曲目作键会让这些与切歌无关的更新也触发一次过渡。
 */
@Immutable
internal data class TrackSwitchAnchor(
    val id: Long?,
    val index: Int,
    val title: String,
    val artist: String,
)

/** 由当前曲目与播放列表下标构造切歌锚点；曲目为 null 时得到空锚点，用于呈现空态文本。 */
@Composable
internal fun rememberTrackSwitchAnchor(track: MusicTrack?, index: Int): TrackSwitchAnchor =
    remember(track?.id, index, track?.title, track?.artist) {
        TrackSwitchAnchor(
            id = track?.id,
            index = index,
            title = track?.title.orEmpty(),
            artist = track?.artist.orEmpty(),
        )
    }

/**
 * 切歌过渡：锚点变化时新旧内容交叠淡入淡出，[slide] 为真时另按切歌方向做一次小幅横移
 * ——下一曲自右侧进入，上一曲自左侧进入，切换方向因此可读。
 *
 * 横移仅适用于自身有边界的元素。铺满整屏且带羽化边缘的沉浸封面不能用横移：
 * 位移期间羽化边会移入视口，露出背景形成一道可辨的接缝，此类内容一律只取淡入淡出。
 *
 * 下标未变（如替换当前曲目）时方向不可知，退化为纯淡入淡出。
 */
@Composable
internal fun TrackSwitchTransition(
    anchor: TrackSwitchAnchor,
    modifier: Modifier = Modifier,
    slide: Boolean = false,
    content: @Composable (TrackSwitchAnchor) -> Unit,
) {
    AnimatedContent(
        targetState = anchor,
        modifier = modifier,
        transitionSpec = {
            val direction = (targetState.index - initialState.index).coerceIn(-1, 1)
            val enter = fadeIn(tween(TRACK_SWITCH_ENTER_MS))
            val exit = fadeOut(tween(TRACK_SWITCH_EXIT_MS))
            if (!slide || direction == 0) {
                enter togetherWith exit
            } else {
                val shift = TRACK_SWITCH_SLIDE_FRACTION * direction
                (enter + slideInHorizontally(tween(TRACK_SWITCH_ENTER_MS)) { (it * shift).roundToInt() })
                    .togetherWith(
                        exit + slideOutHorizontally(tween(TRACK_SWITCH_EXIT_MS)) { (-it * shift).roundToInt() }
                    )
            }
        },
        label = "trackSwitch",
        content = { state -> content(state) },
    )
}
