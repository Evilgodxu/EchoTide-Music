package com.yichao.evilgodxu.screens.home

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yichao.evilgodxu.LocalApplication
import com.yichao.evilgodxu.LocalMetadataEnricher
import com.yichao.evilgodxu.LocalMusicPanelStateHolder
import com.yichao.evilgodxu.LocalPlaylistRefresher
import com.yichao.evilgodxu.screens.home.compact.CompactAssembly
import com.yichao.evilgodxu.screens.home.component.panel.HomePage
import com.yichao.evilgodxu.screens.home.component.panel.rememberHomePanelState
import com.yichao.evilgodxu.screens.home.expanded.ExpandedAssembly
import com.yichao.evilgodxu.theme.SystemBarAppearance
import com.yichao.evilgodxu.windowsize.rememberExpandedForm
import com.yichao.evilgodxu.windowsize.rememberWindowLandscape

// 页面入口：形态分发 + 跨形态副作用，不承载布局
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit,
) {
    val application = LocalApplication.current
    val stateHolder = LocalMusicPanelStateHolder.current
    val playlistRefresher = LocalPlaylistRefresher.current
    val metadataEnricher = LocalMetadataEnricher.current
    val viewModel: HomeViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                HomeViewModel(
                    application = application,
                    stateHolder = stateHolder,
                    playlistRefresher = playlistRefresher,
                    metadataEnricher = metadataEnricher,
                )
            }
        },
    )
    val uiState by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // 冷启动恢复持久化的播放列表，并定位当前曲目
    LaunchedEffect(Unit) {
        val state = stateHolder.state
        state.restoreSavedState(context)
        if (state.playlist.isNotEmpty() && state.currentTrack == null) {
            val index = state.pendingSavedUri
                ?.let { uri -> state.playlist.indexOfFirst { it.audioUri == uri } }
                ?.takeIf { it >= 0 }
                ?: 0
            state.currentIndex = index
            state.currentTrack = state.playlist[index]
        }
        // 冷启动阶段尚无控制器时长，用缓存曲目时长补全，使进度条能按已还原的位置展示进度（伴随过渡动画）
        if (state.duration <= 0L) {
            state.currentTrack?.let { state.duration = it.duration }
        }
        // 后台预读当前曲目歌词：不阻塞首帧，歌词页打开时已就绪
        state.requestMetadata(state.currentTrack)
        // 未播放时也预读当前曲目格式信息，重启后音频信息条仍能展示
        state.refreshIdleTrackFormatInfo(context)
    }

    val activity = LocalActivityResultRegistryOwner.current as? Activity
    // 朝向以窗口实测宽高比为准：Activity 自行处理方向变更，配置读取可能不随之刷新
    val isPortrait = !rememberWindowLandscape()

    // 系统栏只声明请求、不直接操作窗口：由 Activity 统一收敛下发，避免此处 show/hide 与窗口切换竞争
    // 竖屏首页沉浸式仅隐藏状态栏；横屏全部隐藏由 Activity 按窗口朝向判定，无需在此重复声明
    DisposableEffect(isPortrait) {
        SystemBarAppearance.isHomePortraitImmersive = isPortrait
        onDispose {
            // 离开首页时撤销请求，避免影响后续页面
            SystemBarAppearance.isHomePortraitImmersive = false
        }
    }
    // 横竖屏切换：按当前朝向请求目标方向
    val onToggleLandscape = {
        activity?.requestedOrientation = if (isPortrait) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // 跨形态共享状态：旋转不重建 Activity，页面位置与后台分析需在形态切换间保持
    val panelState = rememberHomePanelState()
    val playbackState = panelState.playbackState.state
    val pagerState = panelState.pagerState

    // 在线搜索页返回键：优先清空搜索结果与输入框；搜索状态已清空时才返回播放器
    BackHandler(enabled = panelState.currentPage == HomePage.SEARCH) {
        val hasSearchContent = playbackState.searchQuery.isNotBlank() ||
            playbackState.searchResults.isNotEmpty() ||
            playbackState.showSearchResults
        if (hasSearchContent) {
            playbackState.setSearchQuery("")
            playbackState.searchResults = emptyList()
            playbackState.searchPending = emptyList()
            playbackState.searchPendingFull = false
            playbackState.setSearchResultsVisible(false)
            playbackState.setErrorMsg(null)
        } else {
            panelState.closePanels()
            playbackState.setSearchResultsVisible(false)
            playbackState.setErrorMsg(null)
        }
    }
    // 歌单页返回键回到播放器
    BackHandler(enabled = panelState.currentPage == HomePage.PLAYLIST) {
        panelState.closePanels()
    }
    // 离开搜索页即清空结果可见性与错误提示，避免下次进入残留上一次搜索的状态
    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage != HomePage.SEARCH.ordinal) {
            playbackState.setSearchResultsVisible(false)
            playbackState.setErrorMsg(null)
        }
    }
    // 只有播放器真正开始播放（无错误）时才返回播放器页；播放失败出现错误提示时留在搜索页
    LaunchedEffect(playbackState.isPlaying) {
        if (playbackState.isPlaying && panelState.currentPage == HomePage.SEARCH) {
            panelState.closePanels()
        }
    }

    // 形态分派：旋转状态与窗口宽度尺寸类共同决定显示内容
    if (rememberExpandedForm()) {
        ExpandedAssembly(
            uiState = uiState,
            panelState = panelState,
            onOpenSettings = onOpenSettings,
            onToggleLandscape = onToggleLandscape,
            onRefreshPermissions = viewModel::refreshPermissions,
            onStartPermissionMonitor = viewModel::startPermissionMonitor,
            onStopPermissionMonitor = viewModel::stopPermissionMonitor,
            modifier = modifier,
        )
    } else {
        CompactAssembly(
            uiState = uiState,
            panelState = panelState,
            onOpenSettings = onOpenSettings,
            onToggleLandscape = onToggleLandscape,
            onRefreshPermissions = viewModel::refreshPermissions,
            onStartPermissionMonitor = viewModel::startPermissionMonitor,
            onStopPermissionMonitor = viewModel::stopPermissionMonitor,
            modifier = modifier,
        )
    }
}
