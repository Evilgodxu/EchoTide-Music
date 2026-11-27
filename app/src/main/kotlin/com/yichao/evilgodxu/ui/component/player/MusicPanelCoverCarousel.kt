package com.yichao.evilgodxu.ui.component.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.ui.component.CAROUSEL_COVER_THUMBNAIL_SIZE
import com.yichao.evilgodxu.ui.component.SongImmersiveBackground
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

// 横屏 3D 封面轮播：中心加左右各 3 首，共 7 首同屏。
// 位置、倾斜、缩放与透明度都由连续中心索引推导
private const val VISIBLE_PER_SIDE = 3

@Composable
internal fun CoverCarouselOverlay(
    playlist: List<MusicTrack>,
    currentIndex: Int,
    onTrackSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    if (playlist.isEmpty()) return
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val startIndex = currentIndex.coerceIn(0, playlist.size - 1)
    val lastIndex = playlist.size - 1
    var selectedIndex by remember { mutableIntStateOf(startIndex) }
    // 连续中心索引：滑动叠加临时偏移，吸附时弹簧回整数位
    val centerIndex = remember { Animatable(startIndex.toFloat()) }
    var dragShift by remember { mutableFloatStateOf(0f) }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize()
    ) {
        // 中心封面与横屏播放器同宽，高度受限时收缩
        val landscapeCoverWidth = maxWidth.value * 0.5f * 0.68f * 0.7f
        val coverSize = minOf(landscapeCoverWidth, maxHeight.value * 0.55f).dp
        // 间距小于封面尺寸，两侧部分重叠形成前后交错
        val spacingPx = with(density) { (coverSize * 0.72f).toPx() }
        // 低刚度弹簧，滑动与点击共用自然过渡
        val settleSpec = spring<Float>(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessLow,
        )

        // 受动画驱动的连续中心位：拖动实时叠加
        val rendered = centerIndex.value + dragShift

        // 背景沿用首页沉浸背景；无方向，按默认交叠淡出
        SongImmersiveBackground(
            track = playlist[rendered.roundToInt().coerceIn(0, lastIndex)],
        )

        // 交互层：滑动切换、点封面选取、点空白收起
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(playlist.size) {
                    detectDragGestures(
                        onDragStart = { scope.launch { centerIndex.stop() } },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragShift -= dragAmount.x / spacingPx
                            // 限制在播放列表范围内
                            val clamped = (centerIndex.value + dragShift).coerceIn(0f, lastIndex.toFloat())
                            dragShift = clamped - centerIndex.value
                        },
                        onDragEnd = {
                            scope.launch {
                                val now = (centerIndex.value + dragShift).coerceIn(0f, lastIndex.toFloat())
                                val target = now.roundToInt()
                                // 并入临时偏移后弹簧过渡到整数位
                                centerIndex.snapTo(now)
                                dragShift = 0f
                                centerIndex.animateTo(target.toFloat(), animationSpec = settleSpec)
                                selectedIndex = target
                            }
                        }
                    )
                }
                .pointerInput(playlist.size) {
                    detectTapGestures { onDismiss() }
                },
            contentAlignment = Alignment.Center
        ) {
            // 渲染中心附近封面，含过渡余量
            val windowCenter = rendered.roundToInt().coerceIn(0, lastIndex)
            val windowStart = (windowCenter - VISIBLE_PER_SIDE - 1).coerceAtLeast(0)
            val windowEnd = (windowCenter + VISIBLE_PER_SIDE + 1).coerceAtMost(lastIndex)
            for (i in windowStart..windowEnd) {
                // 以歌曲 id 为稳定 key，窗口偏移不重建
                key(playlist[i].id) {
                    val dist = i - rendered
                    Box(
                        modifier = Modifier
                            .offset { IntOffset((dist * spacingPx).roundToInt(), 0) }
                            .size(coverSize)
                            // 越近中心层级越高
                            .zIndex(-abs(dist))
                            .graphicsLayer {
                                // 远端旋转收敛，避免视角过陡
                                rotationY = (-dist * 10f).coerceIn(-50f, 50f)
                                scaleX = (1f - abs(dist) * 0.12f).coerceAtLeast(0.7f)
                                scaleY = scaleX
                                alpha = (1f - abs(dist) * 0.16f).coerceIn(0.5f, 1f)
                            }
                            .clip(RoundedCornerShape(16.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {
                                    if (i == selectedIndex) {
                                        onTrackSelected(i)
                                    } else {
                                        // 点击侧边封面：弹簧过渡到中心
                                        scope.launch {
                                            centerIndex.stop()
                                            centerIndex.animateTo(i.toFloat(), animationSpec = settleSpec)
                                            dragShift = 0f
                                            selectedIndex = i
                                        }
                                    }
                                }
                            )
                    ) {
                        // 统一 512 档，7 张共用同一份缓存
                        AlbumArt(
                            track = playlist[i],
                            modifier = Modifier.fillMaxSize(),
                            thumbnailSize = CAROUSEL_COVER_THUMBNAIL_SIZE,
                        )
                    }
                }
            }
        }

        // 底部居中显示标题与艺术家
        val track = playlist[rendered.roundToInt().coerceIn(0, lastIndex)]
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = track.title,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = track.artist,
                color = Color.White.copy(alpha = 0.72f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1
            )
        }
    }
}
