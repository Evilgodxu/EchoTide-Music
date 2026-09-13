package com.yichao.evilgodxu.screens.home.expanded.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.settings.landscapeLyricLayoutFlow
import com.yichao.evilgodxu.data.settings.LandscapeLyricLayoutParams
import com.yichao.evilgodxu.data.settings.LyricLayoutDefaults
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.data.music.playback.playTrackAt
import com.yichao.evilgodxu.screens.home.component.dialog.LosslessUpgradeDialog
import com.yichao.evilgodxu.screens.home.component.player.HomeAlbumArt
import com.yichao.evilgodxu.screens.home.component.player.MarqueeInfoLine
import com.yichao.evilgodxu.screens.home.component.player.PlayerControls
import com.yichao.evilgodxu.screens.home.component.queue.PlaylistSheet
import com.yichao.evilgodxu.ui.component.currentTrackNeedsLosslessUpgrade
import com.yichao.evilgodxu.ui.component.TrackFormatInfoSection
import com.yichao.evilgodxu.ui.component.VerticalProgressBar
import com.yichao.evilgodxu.ui.component.CoverCarouselOverlay
import com.yichao.evilgodxu.ui.component.LyricsPanel
import kotlinx.coroutines.launch

// 横屏播放器：双栏结构（封面视觉区 → 歌词透视区）左右居中，标题栏与控制栏点击弹出
@Composable
fun LandscapePlayer(
    playbackState: MusicPlaybackState,
    chromeVisible: Boolean,
    onToggleChrome: () -> Unit,
    // 播放列表面板显隐：由首页层持有，显示期间禁用上下滑动切歌
    playlistVisible: Boolean,
    onPlaylistVisibilityChange: (Boolean) -> Unit,
    // 长按上一曲/下一曲唤出调速对话框：弹窗宿主在首页对话框层，位于控制栏显隐子树之外
    onSpeedLongClick: () -> Unit,
    // 3D 封面轮播显隐：由首页层持有，进入沉浸覆盖层时同步隐藏标题栏与控制栏
    coverCarouselVisible: Boolean,
    onCoverCarouselVisibilityChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 无损升级确认对话框显隐
    var showLosslessUpgrade by remember { mutableStateOf(false) }
    // 封面与点击检测层在窗口坐标系下的位置，用于判定点击是否命中封面
    var tapBounds by remember { mutableStateOf<Rect?>(null) }
    var coverBounds by remember { mutableStateOf<Rect?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 覆盖层开启时，系统返回键收起 3D 封面轮播
    BackHandler(enabled = coverCarouselVisible) { onCoverCarouselVisibilityChange(false) }
    // 播放列表面板开启时，系统返回键优先收起面板，避免直接落到首页退出逻辑
    BackHandler(enabled = playlistVisible) { onPlaylistVisibilityChange(false) }

    // 播放进度由 MusicPlaybackState 全局 ticker 驱动，此处不再独立轮询

    Box(modifier = modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxSize()) {
            // 左：封面视觉区，封面下方叠放标题与艺术家两行信息，水平居中
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(start = 24.dp, end = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                val track = playbackState.currentTrack
                if (track != null) {
                    CoverInfo(
                        track = track,
                        onCoverBounds = { coverBounds = it },
                        modifier = Modifier.fillMaxWidth(LANDSCAPE_COVER_FRACTION),
                    )
                }
            }
            // 右：歌词透视区，水平居中
            LyricsPerspectiveZone(
                playbackState = playbackState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
        }

        // 全屏点击检测：命中封面区域则进入 3D 轮播；点击其它区域切换标题栏与控制栏显隐
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { tapBounds = it.boundsInWindow() }
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        if (coverCarouselVisible) return@detectTapGestures
                        val tap = tapBounds ?: return@detectTapGestures
                        val cover = coverBounds ?: return@detectTapGestures
                        val windowPoint = Offset(tap.left + offset.x, tap.top + offset.y)
                        if (cover.contains(windowPoint)) {
                            onCoverCarouselVisibilityChange(true)
                        } else {
                            onToggleChrome()
                        }
                    }
                },
        )

        // 封面与歌词之间的竖向进度条（白色样式，不带时间文本，拖动可跳转）
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxHeight(0.5f)
                .width(80.dp),
        ) {
            VerticalProgressBar(
                playbackState = playbackState,
                contentColor = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxHeight(),
            )
        }

        // 底部控制栏
        AnimatedVisibility(
            visible = chromeVisible,
            enter = slideInVertically(animationSpec = tween(300)) { it } + fadeIn(),
            exit = slideOutVertically(animationSpec = tween(300)) { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Transparent),
            ) {
                TrackFormatInfoSection(
                    playbackState = playbackState,
                    contentColor = Color.White,
                    onClick = {
                        if (currentTrackNeedsLosslessUpgrade(playbackState)) {
                            showLosslessUpgrade = true
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                )
                PlayerControls(
                    playbackState = playbackState,
                    onPlaylistClick = { onPlaylistVisibilityChange(true) },
                    onSpeedLongClick = onSpeedLongClick,
                )
            }
        }

        PlaylistSheet(
            visible = playlistVisible,
            playbackState = playbackState,
            onDismiss = { onPlaylistVisibilityChange(false) },
        )

        // 音频信息条点击触发的无损升级确认对话框
        LosslessUpgradeDialog(
            visible = showLosslessUpgrade,
            playbackState = playbackState,
            onDismiss = { showLosslessUpgrade = false },
        )

        // 3D 封面轮播覆盖全屏，淡入 + 轻微缩放过渡入场，避免闪屏且衔接自然
        AnimatedVisibility(
            visible = coverCarouselVisible && playbackState.playlist.isNotEmpty(),
            enter = fadeIn(animationSpec = tween(300)) + scaleIn(
                initialScale = 1.15f,
                animationSpec = tween(300)
            ),
            exit = fadeOut(animationSpec = tween(200)) + scaleOut(
                targetScale = 1.05f,
                animationSpec = tween(200)
            ),
            modifier = Modifier.fillMaxSize(),
        ) {
            CoverCarouselOverlay(
                playlist = playbackState.playlist,
                currentIndex = playbackState.currentIndex.coerceAtLeast(0),
                onTrackSelected = { index ->
                    scope.launch { playTrackAt(context, playbackState, index) }
                    onCoverCarouselVisibilityChange(false)
                },
                onDismiss = { onCoverCarouselVisibilityChange(false) },
            )
        }
    }
}

// 3D 透视基准参数：强度为 1 时的旋转角与相机距离系数
private const val BASE_ROTATION_Y_DEGREES = -45f
private const val BASE_CAMERA_DISTANCE_FACTOR = 0.15f

// 歌词透视区：rotationY 绕 Y 轴旋转，配合随宽度缩放的 cameraDistance 产生近大远小的真实 3D 透视
@Composable
private fun LyricsPerspectiveZone(
    playbackState: MusicPlaybackState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val landscapeLayout by context.landscapeLyricLayoutFlow()
        .collectAsStateWithLifecycle(
            initialValue = LandscapeLyricLayoutParams(
                LyricLayoutDefaults.LANDSCAPE_FONT_SIZE_SP,
                LyricLayoutDefaults.LANDSCAPE_VISIBLE_LINES,
                LyricLayoutDefaults.LANDSCAPE_3D_INTENSITY,
            ),
        )
    // 强度映射：旋转角随强度增大，相机距离随强度减小，透视随之增强
    val intensity = landscapeLayout.threeDIntensity
    val rotationYDegrees = (BASE_ROTATION_Y_DEGREES * intensity).coerceIn(-75f, 0f)
    val cameraDistanceFactor = if (intensity > 0f) {
        (BASE_CAMERA_DISTANCE_FACTOR / intensity).coerceAtLeast(0.05f)
    } else 1f
    Box(
        modifier = modifier
            .padding(start = 12.dp, end = 24.dp, top = 12.dp, bottom = 12.dp)
            .clipToBounds()
            .graphicsLayer {
                rotationY = rotationYDegrees
                // 官方推荐：cameraDistance 不小于视图宽度，透视才自然且不畸变
                cameraDistance = size.width * cameraDistanceFactor
            },
        contentAlignment = Alignment.Center,
    ) {
        LyricsPanel(
            playbackState = playbackState,
            onClick = {},
            fontSize = landscapeLayout.fontSizeSp.sp,
            contentColor = Color.White,
            visibleLines = landscapeLayout.visibleLines,
        )
    }
}

// 横屏封面宽度占比：基准 0.68 缩放 70%
private const val LANDSCAPE_COVER_FRACTION = 0.68f * 0.7f
// 封面下方文本行宽度 = 封面宽度的 80%
private const val INFO_WIDTH_FRACTION = 0.8f

// 封面信息区：封面 + 封面宽度 80% 的标题与艺术家两行，溢出时跑马灯滚动并叠加歌词同款边缘渐变
@Composable
private fun CoverInfo(
    track: MusicTrack,
    onCoverBounds: (Rect?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        HomeAlbumArt(
            track = track,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(24.dp))
                .onGloballyPositioned { coords -> onCoverBounds(coords.boundsInWindow()) },
        )
        Spacer(Modifier.height(16.dp))
        MarqueeInfoLine(
            text = track.title,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            modifier = Modifier.fillMaxWidth(INFO_WIDTH_FRACTION),
        )
        Spacer(Modifier.height(6.dp))
        MarqueeInfoLine(
            text = track.artist,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White.copy(alpha = 0.72f),
            modifier = Modifier.fillMaxWidth(INFO_WIDTH_FRACTION),
        )
    }
}
