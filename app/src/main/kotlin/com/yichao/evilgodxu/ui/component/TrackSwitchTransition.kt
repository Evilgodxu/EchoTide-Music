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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.playback.TrackSwitchKind

// 横移时长：入场与退场必须同长同速，两层才能始终首尾相接拼满容器。
// 时长不等会让先到的一层提前离场，容器上留下透出底色的空档
private const val TRACK_SLIDE_MS = 320

// 交叠淡入淡出时长：入场略长于退场，新内容先立住，旧内容在其后收干净
private const val TRACK_FADE_ENTER_MS = 320
private const val TRACK_FADE_EXIT_MS = 220

// 元素级切歌过渡方式
internal enum class TrackSwitchStyle {
    // 横移：新旧内容各整幅移出/移入，过渡期间两层拼满容器，全程不透明。
    // 交叠淡出期间两层各半透明，会透出容器底色形成一次亮度塌陷，铺满容器者一律走横移
    Slide,

    // 交叠淡入淡出：用于迷你播放器、音乐面板这类独立窗口——它们无从取得整屏揭示，
    // 元素又都嵌在固定的小格子里，整幅横移会越出格位
    Crossfade,
}

/**
 * 切歌过渡锚点：一个过渡层要显示的全部内容——曲目本身与触发本次变更的类型。
 *
 * 曲目以整份实例承载而非仅存标识：退场的那一层要显示上一首的封面与文案，
 * 只持标识就无从取图。过渡是否发生由曲目标识判定（见 [TrackSwitchTransition] 的 contentKey），
 * 与实例无关，歌词补全等元数据更新替换实例时不会误触发一次过渡。
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

/** 由当前曲目与触发本次变更的类型构造锚点；曲目为 null 时得到空锚点，用于呈现空态。 */
@Composable
internal fun rememberTrackSwitchAnchor(track: MusicTrack?, kind: TrackSwitchKind): TrackSwitchAnchor =
    remember(track, kind) { TrackSwitchAnchor(track = track, kind = kind) }

/**
 * 切歌过渡：新旧内容交叠，交叠方式由 [style] 决定。
 *
 * 横移方向取自锚点里的类型：上一曲自左侧移入（向右侧移出）、下一曲自右侧移入（向左侧移出）。
 * 方向不取播放列表下标差——随机播放下标差读不出前后，而类型本身始终可读。
 * 无方向可读的类型（选曲播放、内容同源的变更）不做元素级过渡，直接替换：
 * 前者换画面交给整屏揭示，元素再自行位移反而与揭示的前沿错位；后者内容一致，位移只会添乱。
 */
@Composable
internal fun TrackSwitchTransition(
    anchor: TrackSwitchAnchor,
    modifier: Modifier = Modifier,
    style: TrackSwitchStyle = TrackSwitchStyle.Slide,
    content: @Composable (TrackSwitchAnchor) -> Unit,
) {
    // 入场方向在过渡规格之外定下：规格里要读的入场方类型，就是本次锚点的类型
    val kind = anchor.kind
    AnimatedContent(
        targetState = anchor,
        modifier = modifier,
        // 以曲目标识为过渡键：锚点里的曲目实例会随元数据补全被替换，用实例作键会误触发过渡
        contentKey = { it.id },
        transitionSpec = {
            when (style) {
                TrackSwitchStyle.Crossfade ->
                    fadeIn(tween(TRACK_FADE_ENTER_MS)) togetherWith fadeOut(tween(TRACK_FADE_EXIT_MS))

                TrackSwitchStyle.Slide -> when (kind) {
                    TrackSwitchKind.Previous -> trackSlideTransform(enterFromLeft = true)
                    TrackSwitchKind.Next -> trackSlideTransform(enterFromLeft = false)
                    // 选曲播放的换画面交给整屏揭示；内容同源的变更本就看不出变化，
                    // 两者都不做元素级过渡，直接替换
                    TrackSwitchKind.Select, TrackSwitchKind.SameContent -> noElementTransition
                }
            }
        },
        label = "trackSwitch",
        content = { state -> content(state) },
    )
}

/** 不做元素级过渡：新旧内容直接替换，用于画面变化由外层整屏揭示接手、或新旧内容同源的场景。 */
internal val noElementTransition: ContentTransform =
    EnterTransition.None togetherWith ExitTransition.None

/**
 * 整幅横移过渡：新内容自一侧整幅移入，旧内容向另一侧等速移出。
 *
 * [enterFromLeft] 决定新内容自哪一侧进入，也就是切歌的方向；
 * 两侧位移幅度同为容器宽度，两层在过渡全程首尾相接，容器不会露出底色。
 */
internal fun trackSlideTransform(enterFromLeft: Boolean): ContentTransform {
    val enter: (Int) -> Int = if (enterFromLeft) { width -> -width } else { width -> width }
    val exit: (Int) -> Int = if (enterFromLeft) { width -> width } else { width -> -width }
    return slideInHorizontally(tween(TRACK_SLIDE_MS), enter)
        .togetherWith(slideOutHorizontally(tween(TRACK_SLIDE_MS), exit))
}
