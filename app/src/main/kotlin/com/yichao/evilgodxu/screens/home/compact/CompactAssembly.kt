package com.yichao.evilgodxu.screens.home.compact

import android.app.Activity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.yichao.evilgodxu.permission.PermissionType
import com.yichao.evilgodxu.data.music.panel.performSearch
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.screens.home.compact.player.PortraitPlayer
import com.yichao.evilgodxu.screens.home.component.bar.HomeTopBar
import com.yichao.evilgodxu.screens.home.component.dialog.HomeDialogs
import com.yichao.evilgodxu.screens.home.component.panel.HomePage
import com.yichao.evilgodxu.screens.home.component.panel.HomePanels
import com.yichao.evilgodxu.screens.home.component.panel.HomePanelState
import com.yichao.evilgodxu.screens.home.component.shell.HomeShell
import com.yichao.evilgodxu.screens.home.component.swipe.rememberHomeTrackSwipeGesture
import com.yichao.evilgodxu.screens.home.HomeUiState
import kotlinx.coroutines.launch

// 窄屏组装器：常驻标题栏 + 竖屏播放器主体
@Composable
internal fun CompactAssembly(
    uiState: HomeUiState,
    panelState: HomePanelState,
    onOpenSettings: () -> Unit,
    onToggleLandscape: () -> Unit,
    onRefreshPermissions: () -> Unit,
    onStartPermissionMonitor: (PermissionType, Activity) -> Unit,
    onStopPermissionMonitor: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playbackState = panelState.playbackState.state
    val context = LocalContext.current
    // 标题/艺术家在线搜索等协程作用域
    val scope = rememberCoroutineScope()
    val currentTrackId = playbackState.currentTrack?.id
    val isLiked = currentTrackId?.let { playbackState.likedIds.contains(it) } ?: false
    // 纵向切歌手势：挂在播放器页上，横向翻页由 Pager 承担
    val trackSwipe = rememberHomeTrackSwipeGesture(
        playbackState = playbackState,
        playlistSheetVisible = panelState.playlistVisible,
    )
    // 对话框收起后的后台分析进度：在标题区居中展示
    val analysisCenterTitle = if (!panelState.libraryAnalysis.visible && panelState.libraryAnalysis.analyzing) {
        panelState.libraryAnalysis.checkingProgress?.let { (checked, total) ->
            stringResource(R.string.library_analysis_check_progress, checked, total)
        } ?: stringResource(R.string.library_analysis_checking)
    } else {
        null
    }
    // 逐字对齐转入后台后的进度：与曲库分析同方案在标题区展示，实时反映已对齐行数
    val wordAlignCenterTitle = if (!panelState.lyricsAlignment.visible && panelState.lyricsAlignment.aligning) {
        panelState.lyricsAlignment.progress?.let { (done, total) ->
            stringResource(R.string.music_panel_word_align_progress, done, total)
        } ?: stringResource(R.string.music_panel_word_align_decoding)
    } else {
        null
    }

    HomeShell(
        panelState = panelState,
        darkenStatusBarArea = true,
        modifier = modifier,
        topBar = {
            HomeTopBar(
                playbackState = playbackState,
                isLiked = isLiked,
                favoriteEnabled = currentTrackId != null,
                centerTitle = wordAlignCenterTitle ?: analysisCenterTitle,
                onShowTimer = { panelState.showTimer = true },
                onToggleFavorite = { currentTrackId?.let { playbackState.toggleFavorite(it) } },
                onToggleLandscape = onToggleLandscape,
                onOpenSettings = onOpenSettings,
            )
        },
    ) { topInset ->
        HomePanels(
            panelState = panelState,
            topInset = topInset,
        ) {
            // 播放器页铺满全屏（含标题栏区域），竖屏沉浸封面嵌入标题栏后方
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(trackSwipe.modifier),
            ) {
                PortraitPlayer(
                    modifier = Modifier.fillMaxSize(),
                    topBarInset = topInset,
                    swipePreviewText = trackSwipe.previewText,
                    libraryAnalysis = panelState.libraryAnalysis,
                    lyricsAlignment = panelState.lyricsAlignment,
                    playlistVisible = panelState.playlistVisible,
                    onPlaylistVisibilityChange = { panelState.playlistVisible = it },
                    onSpeedLongClick = { panelState.showSpeed = true },
                    // 长按标题/艺术家菜单"在线搜索"：切到在线搜索页并自动按当前菜单文本搜索
                    onOpenOnlineSearch = { query ->
                        playbackState.setSearchQuery(query)
                        playbackState.setSearchResultsVisible(true)
                        panelState.goToPage(HomePage.SEARCH)
                        scope.launch { performSearch(playbackState, context) }
                    },
                )
            }
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
