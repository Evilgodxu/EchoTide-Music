package com.yichao.evilgodxu.screens.home

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yichao.evilgodxu.LocalApplication
import com.yichao.evilgodxu.LocalMetadataEnricher
import com.yichao.evilgodxu.LocalPlaybackState
import com.yichao.evilgodxu.LocalPlaylistRefresher
import com.yichao.evilgodxu.navigation.LocalIsTopLayer
import com.yichao.evilgodxu.screens.home.compact.CompactAssembly
import com.yichao.evilgodxu.screens.home.component.panel.HomePage
import com.yichao.evilgodxu.screens.home.component.panel.HomePanelState
import com.yichao.evilgodxu.screens.home.component.panel.rememberHomePanelState
import com.yichao.evilgodxu.screens.home.component.permission.StartupPermissionChain
import com.yichao.evilgodxu.screens.home.expanded.ExpandedAssembly
import com.yichao.evilgodxu.theme.LocalIsDarkTheme
import com.yichao.evilgodxu.theme.SystemBarAppearance
import com.yichao.evilgodxu.windowsize.rememberExpandedForm
import com.yichao.evilgodxu.windowsize.rememberWindowLandscape

// 页面入口：依赖连接与副作用，布局交给组装器
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    // 高级菜单「查看频谱」：跳转频谱页
    onOpenSpectrum: (Long) -> Unit,
    // 高级菜单「编辑元数据」：跳转元数据页
    onOpenMetadata: (Long) -> Unit,
) {
    // 被覆盖层压住时让出返回键与系统栏写入
    val isTopLayer = LocalIsTopLayer.current
    // ViewModel 工厂在非组合上下文执行，依赖先取出
    val application = LocalApplication.current
    val playbackState = LocalPlaybackState.current
    val playlistRefresher = LocalPlaylistRefresher.current
    val metadataEnricher = LocalMetadataEnricher.current
    val viewModel: HomeViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                HomeViewModel(
                    application = application,
                    playbackState = playbackState,
                    playlistRefresher = playlistRefresher,
                    metadataEnricher = metadataEnricher,
                )
            }
        },
    )
    val uiState by viewModel.state.collectAsStateWithLifecycle()

    StartupPermissionChain(
        mediaAudioGranted = uiState.mediaAudioGranted,
        onAudioGranted = viewModel::refreshPermissions,
        markRequested = viewModel::markPermissionRequested,
    )
    // 回前台复检授权，系统设置页授权没有弹窗回调
    LifecycleResumeEffect(Unit) {
        viewModel.refreshPermissions()
        onPauseOrDispose { }
    }

    // 共享状态：旋转不重建 Activity，需跨形态保持
    val panelState = rememberHomePanelState()
    val onToggleLandscape = landscapeToggleAction()

    HomeSystemBarsEffect(panelState, isTopLayer)
    HomeBackHandlers(panelState, enabled = isTopLayer)
    SearchPageSyncEffects(panelState)

    if (rememberExpandedForm()) {
        ExpandedAssembly(
            uiState = uiState,
            panelState = panelState,
            onOpenSettings = onOpenSettings,
            onOpenSpectrum = onOpenSpectrum,
            onOpenMetadata = onOpenMetadata,
            onToggleLandscape = onToggleLandscape,
        )
    } else {
        CompactAssembly(
            uiState = uiState,
            panelState = panelState,
            onOpenSettings = onOpenSettings,
            onOpenSpectrum = onOpenSpectrum,
            onOpenMetadata = onOpenMetadata,
            onToggleLandscape = onToggleLandscape,
        )
    }
}

// 横竖屏切换：朝向按窗口宽高比，配置可能不刷新
@Composable
private fun landscapeToggleAction(): () -> Unit {
    val activity = LocalActivityResultRegistryOwner.current as? Activity
    val isPortrait = !rememberWindowLandscape()
    return {
        activity?.requestedOrientation = if (isPortrait) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
}

// 首页背景恒深色，状态栏图标固定白色。
// 底部弹层展开时导航栏落在浅色面板，改按主题取色。
@Composable
private fun HomeSystemBarsEffect(panelState: HomePanelState, isTopLayer: Boolean) {
    val sheetOnTop = panelState.playlistVisible ||
        panelState.audioInfoVisible ||
        panelState.libraryAnalysis.visible
    val lightNavigationBars = sheetOnTop && !LocalIsDarkTheme.current
    SideEffect {
        // 被覆盖层压住时不写入，否则会覆盖上层外观
        if (!isTopLayer) return@SideEffect
        SystemBarAppearance.isLightStatusBars = false
        SystemBarAppearance.isLightNavigationBars = lightNavigationBars
    }
}

// 两级返回：搜索页先清内容，歌单页直接回；覆盖层压住时让出
@Composable
private fun HomeBackHandlers(panelState: HomePanelState, enabled: Boolean) {
    val playbackState = LocalPlaybackState.current
    BackHandler(enabled = enabled && panelState.currentPage == HomePage.SEARCH) {
        val hasSearchContent = playbackState.searchQuery.isNotBlank() ||
            playbackState.searchResults.isNotEmpty() ||
            playbackState.showSearchResults
        if (hasSearchContent) {
            playbackState.searchPanel.clearSearchResults()
            playbackState.setErrorMsg(null)
        } else {
            panelState.closePanels()
            playbackState.setSearchResultsVisible(false)
            playbackState.setErrorMsg(null)
        }
    }
    BackHandler(enabled = enabled && panelState.currentPage == HomePage.PLAYLIST) {
        panelState.closePanels()
    }
}

// 离开搜索页清空结果与错误；播放成功才回播放器
@Composable
private fun SearchPageSyncEffects(panelState: HomePanelState) {
    val playbackState = LocalPlaybackState.current
    val pagerState = panelState.pagerState
    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage != HomePage.SEARCH.ordinal) {
            playbackState.setSearchResultsVisible(false)
            playbackState.setErrorMsg(null)
        }
    }
    LaunchedEffect(playbackState.isPlaying) {
        if (playbackState.isPlaying && panelState.currentPage == HomePage.SEARCH) {
            panelState.closePanels()
        }
    }
}
