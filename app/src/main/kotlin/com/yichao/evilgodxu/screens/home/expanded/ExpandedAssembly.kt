package com.yichao.evilgodxu.screens.home.expanded

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
import com.yichao.evilgodxu.screens.home.component.bar.HomeTopBar
import com.yichao.evilgodxu.screens.home.component.dialog.ArtistPickerDialog
import com.yichao.evilgodxu.screens.home.component.dialog.HomeDialogs
import com.yichao.evilgodxu.screens.home.component.panel.HomePage
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
    modifier: Modifier = Modifier,
) {
    val playbackState = panelState.playbackState
    val currentTrackId = playbackState.currentTrack?.id
    val isLiked = currentTrackId?.let { playbackState.likedIds.contains(it) } ?: false
    // 纵切歌手势挂播放页，横向交给 Pager
    val trackSwipe = rememberHomeTrackSwipeGesture(
        playbackState = playbackState,
        sheetVisible = panelState.playlistVisible,
    )
    // 横屏下标题栏与控制栏的统一显隐状态
    var chromeVisible by remember { mutableStateOf(false) }
    // 音量调节中不自动收起，否则中断手势
    var volumeAdjusting by remember { mutableStateOf(false) }
    // 播放模式展开时控制栏常显，否则遮罩残留
    var modeSelectorExpanded by remember { mutableStateOf(false) }
    // 3D 轮播显隐；进入时联动隐藏 chrome
    var coverCarouselVisible by remember { mutableStateOf(false) }
    // 待选歌手；放在显隐子树外，收起不中断
    var artistPicker by remember { mutableStateOf<List<String>>(emptyList()) }

    // chrome 3 秒后隐藏，调模式/音量时常显
    LaunchedEffect(chromeVisible, modeSelectorExpanded, volumeAdjusting) {
        if (chromeVisible && !modeSelectorExpanded && !volumeAdjusting) {
            delay(3000)
            chromeVisible = false
        }
    }
    // chrome 收起时复位播放模式选择
    LaunchedEffect(chromeVisible) {
        if (!chromeVisible) modeSelectorExpanded = false
    }
    // 播放列表面板展开时隐藏 chrome
    LaunchedEffect(panelState.playlistVisible) {
        if (panelState.playlistVisible) chromeVisible = false
    }
    // 离开播放页即收起，避免回页残留唤出
    LaunchedEffect(panelState.currentPage) {
        if (panelState.currentPage != HomePage.PLAYER) chromeVisible = false
    }
    // 进入 3D 轮播时收起 chrome
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
            onOpenSettings = onOpenSettings,
        ) {
            // 播放器页铺满全屏
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(trackSwipe.modifier),
            ) {
                LandscapePlayer(
                    playbackState = playbackState,
                    mediaAudioGranted = uiState.mediaAudioGranted,
                    chromeVisible = chromeVisible,
                    onToggleChrome = { chromeVisible = !chromeVisible },
                    modeSelectorExpanded = modeSelectorExpanded,
                    onModeSelectorExpandedChange = { modeSelectorExpanded = it },
                    playlistVisible = panelState.playlistVisible,
                    onPlaylistVisibilityChange = { panelState.playlistVisible = it },
                    onVolumeAdjustingChange = { volumeAdjusting = it },
                    coverCarouselVisible = coverCarouselVisible,
                    onCoverCarouselVisibilityChange = { coverCarouselVisible = it },
                    onOpenSpectrum = onOpenSpectrum,
                    onOpenMetadata = onOpenMetadata,
                    // 歌词快速滑动按切歌处理
                    onVerticalFling = trackSwipe::switchTrack,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        // 标题栏只管播放页，随 chrome 显隐，不挤压布局
        AnimatedVisibility(
            visible = chromeVisible && panelState.currentPage == HomePage.PLAYER,
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
                // 曲名与艺术家居中，随标题栏显隐
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
                                    // 多位先弹选择，单一直接进歌单
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
        // 点击多位歌手后的选择框（在显隐子树外）
        ArtistPickerDialog(
            artists = artistPicker,
            onSelect = { artist ->
                artistPicker = emptyList()
                panelState.openArtistPlaylist(artist)
            },
            onDismiss = { artistPicker = emptyList() },
        )
        HomeDialogs(panelState = panelState)
    }
}
