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

// 页面入口：只做依赖连接、尺寸判定与副作用挂载，布局交给组装器
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    // 播放列表高级菜单的「查看频谱」：跳转频谱分析页，只传曲目标识
    onOpenSpectrum: (Long) -> Unit,
    // 播放列表高级菜单的「编辑元数据」：跳转元数据编辑页，只传曲目标识
    onOpenMetadata: (Long) -> Unit,
) {
    // ViewModel 工厂在非组合上下文中执行，依赖须先在此取出
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
    // 回到前台复检授权：授权也可能发生在系统设置页，那条路没有弹窗回调，
    // 只认回调会让「已在系统里开了权限」的本进程一直停在空库，等下次冷启动才恢复
    LifecycleResumeEffect(Unit) {
        viewModel.refreshPermissions()
        onPauseOrDispose { }
    }

    // 跨形态共享状态：旋转不重建 Activity，页面位置与后台分析需在形态切换间保持
    val panelState = rememberHomePanelState()
    val onToggleLandscape = landscapeToggleAction()

    HomeSystemBarsEffect(panelState)
    HomeBackHandlers(panelState)
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

// 横竖屏切换：朝向以窗口实测宽高比为准，Activity 自行处理方向变更，配置读取可能不随之刷新
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

// 系统栏图标按「图标底下压着什么」定色：首页两条系统栏全沉浸隐藏，图标只在上滑临时唤出时可见，
// 而首页恒为深色封面衍生背景，故固定白色、不沿用上个页面的深色图标；底部弹层展开时导航栏落在
// 跟随主题的面板上，改回按主题取色，否则浅色主题下白图标压在浅色面板上不可辨
@Composable
private fun HomeSystemBarsEffect(panelState: HomePanelState) {
    val sheetOnTop = panelState.playlistVisible ||
        panelState.audioInfoVisible ||
        panelState.libraryAnalysis.visible
    val lightNavigationBars = sheetOnTop && !LocalIsDarkTheme.current
    SideEffect {
        SystemBarAppearance.isLightStatusBars = false
        SystemBarAppearance.isLightNavigationBars = lightNavigationBars
    }
}

// 首页两级返回：搜索页优先清空内容再回播放器，歌单页直接回播放器
@Composable
private fun HomeBackHandlers(panelState: HomePanelState) {
    val playbackState = LocalPlaybackState.current
    BackHandler(enabled = panelState.currentPage == HomePage.SEARCH) {
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
    BackHandler(enabled = panelState.currentPage == HomePage.PLAYLIST) {
        panelState.closePanels()
    }
}

// 搜索页的生命周期同步：离开即清空结果可见性与错误提示，避免下次进入残留上一次搜索的状态；
// 只有播放器真正开始播放（无错误）时才返回播放器页，播放失败时留在搜索页看错误提示
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
