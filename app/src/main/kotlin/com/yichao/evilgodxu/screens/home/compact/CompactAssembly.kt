package com.yichao.evilgodxu.screens.home.compact

import android.app.Activity
import android.view.View
import android.view.WindowInsets as AndroidWindowInsets
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// 竖屏标题栏无操作自动收起延时：与横屏标题栏同为沉浸式显隐，时长按竖屏观感取 2 秒
private const val TopBarAutoHideDelayMs = 2000L
// 竖屏标题栏收起/唤出的淡入淡出时长：与横屏标题栏一致
private const val TopBarFadeDurationMs = 300

// 竖屏沉浸隐藏状态栏后，系统上报的顶部内边距归零。此处按状态栏实际高度预留该区域：
// 标题栏与内容不再嵌入状态栏区域，且与其它页面顶部基准一致，切换页面时不会出现整体位移
@Composable
private fun rememberStatusBarTopInsets(): WindowInsets {
    val view = LocalView.current
    val density = LocalDensity.current
    var insets by remember { mutableStateOf(WindowInsets(0.dp)) }
    DisposableEffect(view, density) {
        fun refresh() {
            val topPx = view.rootWindowInsets
                ?.getInsetsIgnoringVisibility(AndroidWindowInsets.Type.statusBars())
                ?.top ?: 0
            val next = with(density) {
                WindowInsets(left = 0.dp, top = topPx.toDp(), right = 0.dp, bottom = 0.dp)
            }
            if (insets != next) insets = next
        }
        // 状态栏高度随窗口尺寸（旋转、分屏、自由窗口改尺寸）变化，以布局结果为准重新取值
        val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> refresh() }
        view.addOnLayoutChangeListener(listener)
        refresh()
        onDispose { view.removeOnLayoutChangeListener(listener) }
    }
    return insets
}

// 窄屏组装器：播放器页沉浸式标题栏 + 竖屏播放器主体
@Composable
internal fun CompactAssembly(
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
    val context = LocalContext.current
    // 沉浸隐藏状态栏时仍按状态栏高度预留顶部空间：标题栏与内容不嵌入状态栏区域
    val topBarWindowInsets = rememberStatusBarTopInsets()
    // 标题/艺术家在线搜索等协程作用域
    val scope = rememberCoroutineScope()
    val currentTrackId = playbackState.currentTrack?.id
    val isLiked = currentTrackId?.let { playbackState.likedIds.contains(it) } ?: false
    // 纵向切歌手势：挂在播放器页上，横向翻页由 Pager 承担
    val trackSwipe = rememberHomeTrackSwipeGesture(
        playbackState = playbackState,
        sheetVisible = panelState.playlistVisible || panelState.audioInfoVisible,
    )
    // 逐字对齐转入后台后的进度：在标题区居中的展示，实时反映已对齐行数
    val wordAlignCenterTitle = if (!panelState.lyricsAlignment.visible && panelState.lyricsAlignment.aligning) {
        panelState.lyricsAlignment.progress?.let { (done, total) ->
            stringResource(R.string.music_panel_word_align_progress, done, total)
        } ?: stringResource(R.string.music_panel_word_align_decoding)
    } else {
        null
    }

    // 底部弹层（播放列表、音频信息）与曲库分析对话框置顶：展开期间它们占住面板区域，
    // 标题栏整体让位，避免与面板内容相互遮挡
    val sheetOnTop = panelState.playlistVisible || panelState.audioInfoVisible || panelState.libraryAnalysis.visible
    // 触摸唤出需要读到最新的置顶状态：播放页的触摸手势协程只建立一次，
    // 直接捕获组合期的布尔值会一直读到首次组合的旧值，故经 State 转交
    val sheetOnTopState = rememberUpdatedState(sheetOnTop)

    // 标题栏显隐：仅播放器页自动收起（搜索页与歌单页内容从标题栏下方开始，收起会留下空带），
    // 触摸播放页任意位置或切回播放器页即唤出，两秒无操作后收起
    val autoHideTopBar = panelState.currentPage == HomePage.PLAYER
    var topBarVisible by remember { mutableStateOf(true) }
    // 交互计数：每次唤出都自增以重置收起计时，连续触摸期间不会提前收起
    var topBarGeneration by remember { mutableIntStateOf(0) }
    fun revealTopBar() {
        // 弹层置顶期间不唤出：触摸落在弹层上，若照常唤出，标题栏会随每次触摸闪入、
        // 两秒后再自动收起，在弹层上方反复进出
        if (sheetOnTopState.value) return
        topBarVisible = true
        topBarGeneration++
    }
    LaunchedEffect(autoHideTopBar, topBarVisible, topBarGeneration) {
        if (!autoHideTopBar || !topBarVisible) return@LaunchedEffect
        delay(TopBarAutoHideDelayMs)
        topBarVisible = false
    }
    // 切回播放器页时重新展示，收起计时随后重新起算
    LaunchedEffect(panelState.currentPage) {
        if (panelState.currentPage == HomePage.PLAYER) revealTopBar()
    }
    // 底部弹层与曲库分析展开时收起，避免遮挡面板内容
    LaunchedEffect(sheetOnTop) {
        if (sheetOnTop) topBarVisible = false
    }
    val topBarAlpha by animateFloatAsState(
        targetValue = if (topBarVisible || !autoHideTopBar) 1f else 0f,
        animationSpec = tween(durationMillis = TopBarFadeDurationMs),
    )

    HomeShell(
        panelState = panelState,
        modifier = modifier,
        topBar = {
            HomeTopBar(
                playbackState = playbackState,
                isLiked = isLiked,
                favoriteEnabled = currentTrackId != null,
                centerTitle = wordAlignCenterTitle,
                onShowTimer = { panelState.showTimer = true },
                onToggleFavorite = { currentTrackId?.let { playbackState.toggleFavorite(it) } },
                onToggleLandscape = onToggleLandscape,
                onOpenSettings = onOpenSettings,
                interactive = topBarVisible || !autoHideTopBar,
                windowInsets = topBarWindowInsets,
                modifier = Modifier.graphicsLayer {
                    // 收起时上移淡出并移出触摸范围，布局尺寸不变以保证骨架顶部内边距稳定
                    alpha = topBarAlpha
                    translationY = -size.height * (1f - topBarAlpha)
                },
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
                    .then(trackSwipe.modifier)
                    // 只在 Initial 阶段观察、不消费事件：播放页任意触摸都唤出标题栏
                    // （弹层置顶期间除外，判定见 revealTopBar）
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                revealTopBar()
                            }
                        }
                    },
            ) {
                PortraitPlayer(
                    modifier = Modifier.fillMaxSize(),
                    topBarInset = topInset,
                    swipePreviewText = trackSwipe.previewText,
                    // 歌词区快速滑动同样按切歌处理，复用整页纵向切歌的判定与偏好
                    onVerticalFling = trackSwipe::switchTrack,
                    libraryAnalysis = panelState.libraryAnalysis,
                    lyricsAlignment = panelState.lyricsAlignment,
                    playlistVisible = panelState.playlistVisible,
                    onPlaylistVisibilityChange = { panelState.playlistVisible = it },
                    // 播放按钮上滑唤出的音频信息弹窗
                    audioInfoVisible = panelState.audioInfoVisible,
                    onAudioInfoVisibilityChange = { panelState.audioInfoVisible = it },
                    // 长按标题/艺术家菜单「在线搜索」：切到在线搜索页并自动按当前菜单文本搜索
                    onOpenOnlineSearch = { query ->
                        playbackState.setSearchQuery(query)
                        playbackState.setSearchResultsVisible(true)
                        panelState.goToPage(HomePage.SEARCH)
                        scope.launch { performSearch(playbackState, context) }
                    },
                    // 点击歌手信息：切到歌单面板并进入该歌手的曲目列表
                    onOpenArtistPlaylist = panelState::openArtistPlaylist,
                    onOpenSpectrum = onOpenSpectrum,
                    onOpenMetadata = onOpenMetadata,
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
