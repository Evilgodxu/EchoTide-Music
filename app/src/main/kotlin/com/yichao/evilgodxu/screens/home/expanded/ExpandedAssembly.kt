package com.yichao.evilgodxu.screens.home.expanded

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.yichao.evilgodxu.permission.PermissionType
import com.yichao.evilgodxu.screens.home.component.bar.HomeTopBar
import com.yichao.evilgodxu.screens.home.component.dialog.HomeDialogs
import com.yichao.evilgodxu.screens.home.component.panel.HomePanels
import com.yichao.evilgodxu.screens.home.component.panel.HomePanelState
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
        playlistSheetVisible = panelState.playlistVisible,
    )
    // 横屏下标题栏与控制栏的统一显隐状态
    var chromeVisible by remember { mutableStateOf(false) }
    // 3D 封面轮播显隐：与 chrome 同层持有，进入沉浸覆盖层时联动隐藏标题栏与控制栏
    var coverCarouselVisible by remember { mutableStateOf(false) }

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
                    onSpeedLongClick = { panelState.showSpeed = true },
                    coverCarouselVisible = coverCarouselVisible,
                    onCoverCarouselVisibilityChange = { coverCarouselVisible = it },
                    // 点击歌手信息：切到歌单面板并进入该歌手的曲目列表
                    onOpenArtistPlaylist = panelState::openArtistPlaylist,
                    onOpenSpectrum = onOpenSpectrum,
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
            )
        }
        HomeDialogs(
            panelState = panelState,
            uiState = uiState,
            onRefreshPermissions = onRefreshPermissions,
            onStartPermissionMonitor = onStartPermissionMonitor,
            onStopPermissionMonitor = onStopPermissionMonitor,
        )
    }
}
