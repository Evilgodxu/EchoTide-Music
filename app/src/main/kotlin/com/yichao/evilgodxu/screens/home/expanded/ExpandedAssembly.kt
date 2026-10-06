package com.yichao.evilgodxu.screens.home.expanded

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.data.music.playback.parseTrackArtists
import com.yichao.evilgodxu.permission.PermissionType
import com.yichao.evilgodxu.screens.home.component.bar.HomeTopBar
import com.yichao.evilgodxu.screens.home.component.dialog.ArtistPickerDialog
import com.yichao.evilgodxu.screens.home.component.dialog.HomeDialogs
import com.yichao.evilgodxu.screens.home.component.panel.HomePanels
import com.yichao.evilgodxu.screens.home.component.panel.HomePanelState
import com.yichao.evilgodxu.screens.home.component.player.MarqueeInfoLine
import com.yichao.evilgodxu.screens.home.component.shell.HomeShell
import com.yichao.evilgodxu.screens.home.component.swipe.rememberHomeTrackSwipeGesture
import com.yichao.evilgodxu.screens.home.expanded.player.LandscapePlayer
import com.yichao.evilgodxu.screens.home.HomeUiState
import kotlinx.coroutines.delay

// 宽屏组装器：悬浮标题栏 + 横屏播放器主体
@Composable
internal fun ExpandedAssembly(
    uiState: HomeUiState,
    panelState: HomePanelState,
    onOpenSettings: () -> Unit,
    onOpenSpectrum: (Long) -> Unit,
    onOpenMetadata: (Long) -> Unit,
    onToggleLandscape: () -> Unit,
    onRefreshPermissions: () -> Unit,
    onStartPermissionMonitor: (PermissionType, Activity) -> Unit,
    onStopPermissionMonitor: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playbackState = panelState.playbackState.state
    val currentTrackId = playbackState.currentTrack?.id
    val isLiked = currentTrackId?.let { playbackState.likedIds.contains(it) } ?: false
    // 纵向切歌手势：挂在播放器页上，横向翻页由 Pager 承担
    val trackSwipe = rememberHomeTrackSwipeGesture(
        playbackState = playbackState,
        sheetVisible = panelState.playlistVisible,
    )
    // 横屏下标题栏与控制栏的统一显隐状态
    var chromeVisible by remember { mutableStateOf(false) }
    // 3D 封面轮播显隐：与 chrome 同层持有，进入沉浸覆盖层时联动隐藏标题栏与控制栏
    var coverCarouselVisible by remember { mutableStateOf(false) }
    // 点击标题栏中的艺术家行时待选择的歌手候选：多位歌手时弹出选择对话框。
    // 置于标题栏显隐子树之外，标题栏自动收起不会中断已弹出的选择
    var artistPicker by remember { mutableStateOf<List<String>>(emptyList()) }

    // 横屏下标题栏与控制栏显示 3 秒后自动隐藏
    LaunchedEffect(chromeVisible) {
        if (chromeVisible) {
            delay(3000)
            chromeVisible = false
        }
    }
    // 播放列表面板展开时自动隐藏标题栏与控制栏，避免遮挡面板内容
    LaunchedEffect(panelState.playlistVisible) {
        if (panelState.playlistVisible) chromeVisible = false
    }
    // 进入 3D 封面轮播时同样收起标题栏与控制栏，沉浸层独占全屏
    LaunchedEffect(coverCarouselVisible) {
        if (coverCarouselVisible) chromeVisible = false
    }

    HomeShell(
        panelState = panelState,
        modifier = modifier,
    ) { topInset ->
        HomePanels(
            panelState = panelState,
            topInset = topInset,
        ) {
            // 播放器页铺满全屏
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(trackSwipe.modifier),
            ) {
                LandscapePlayer(
                    playbackState = playbackState,
                    chromeVisible = chromeVisible,
                    onToggleChrome = { chromeVisible = !chromeVisible },
                    playlistVisible = panelState.playlistVisible,
                    onPlaylistVisibilityChange = { panelState.playlistVisible = it },
                    coverCarouselVisible = coverCarouselVisible,
                    onCoverCarouselVisibilityChange = { coverCarouselVisible = it },
                    onOpenSpectrum = onOpenSpectrum,
                    onOpenMetadata = onOpenMetadata,
                    // 歌词区快速滑动同样按切歌处理，复用整页纵向切歌的判定与偏好
                    onVerticalFling = trackSwipe::switchTrack,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        // 横屏标题栏悬浮于内容顶部，随控制栏一起显隐，不挤压播放器布局
        AnimatedVisibility(
            visible = chromeVisible,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = slideInVertically(animationSpec = tween(300)) { -it } + fadeIn(),
            exit = slideOutVertically(animationSpec = tween(300)) { -it } + fadeOut(),
        ) {
            HomeTopBar(
                playbackState = playbackState,
                isLiked = isLiked,
                favoriteEnabled = currentTrackId != null,
                windowInsets = WindowInsets(0, 0, 0, 0),
                onShowTimer = { panelState.showTimer = true },
                onToggleFavorite = { currentTrackId?.let { playbackState.toggleFavorite(it) } },
                onToggleLandscape = onToggleLandscape,
                onOpenSettings = onOpenSettings,
                // 曲名与艺术家居中于标题栏，随标题栏一同显隐
                centerContent = {
                    playbackState.currentTrack?.let { track ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            MarqueeInfoLine(
                                text = track.title,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(2.dp))
                            MarqueeInfoLine(
                                text = track.artist,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.White.copy(alpha = 0.72f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    // 多位歌手先弹选择对话框，单一时直接进入该歌手的歌单页
                                    .clickable {
                                        val artists = parseTrackArtists(track.artist)
                                            .filter { it.isNotBlank() }
                                        if (artists.size > 1) {
                                            artistPicker = artists
                                        } else {
                                            artists.firstOrNull()?.let(panelState::openArtistPlaylist)
                                        }
                                    },
                            )
                        }
                    }
                },
            )
        }
        // 标题栏艺术家行点击多位歌手后弹出的选择对话框（宿主在显隐子树之外，收起标题栏不中断选择）
        ArtistPickerDialog(
            artists = artistPicker,
            onSelect = { artist ->
                artistPicker = emptyList()
                panelState.openArtistPlaylist(artist)
            },
            onDismiss = { artistPicker = emptyList() },
        )
        HomeDialogs(
            panelState = panelState,
            uiState = uiState,
            onRefreshPermissions = onRefreshPermissions,
            onStartPermissionMonitor = onStartPermissionMonitor,
            onStopPermissionMonitor = onStopPermissionMonitor,
        )
    }
}
