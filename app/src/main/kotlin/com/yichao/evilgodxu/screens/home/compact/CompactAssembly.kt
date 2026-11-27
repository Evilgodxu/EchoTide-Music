package com.yichao.evilgodxu.screens.home.compact

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
import androidx.compose.ui.unit.dp
import com.yichao.evilgodxu.data.music.panel.performSearch
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

// 标题栏无操作收起延时 2 秒
private const val TopBarAutoHideDelayMs = 2000L
// 标题栏淡入淡出 300ms，与横屏一致
private const val TopBarFadeDurationMs = 300

// 隐藏状态栏后系统顶部内边距归零，按实际高度补齐。
// 否则标题栏嵌入状态栏区，切页时整体位移。
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
        // 状态栏高度随窗口变化，布局后重新取值
        val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> refresh() }
        view.addOnLayoutChangeListener(listener)
        refresh()
        onDispose { view.removeOnLayoutChangeListener(listener) }
    }
    return insets
}

// 窄屏组装器：沉浸标题栏 + 竖屏播放器
@Composable
internal fun CompactAssembly(
    uiState: HomeUiState,
    panelState: HomePanelState,
    onOpenSettings: () -> Unit,
    onOpenSpectrum: (Long) -> Unit,
    onOpenMetadata: (Long) -> Unit,
    onToggleLandscape: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playbackState = panelState.playbackState
    val context = LocalContext.current
    // 仍按状态栏高度预留顶部空间
    val topBarWindowInsets = rememberStatusBarTopInsets()
    // 标题/艺术家在线搜索等协程作用域
    val scope = rememberCoroutineScope()
    val currentTrackId = playbackState.currentTrack?.id
    val isLiked = currentTrackId?.let { playbackState.likedIds.contains(it) } ?: false
    // 纵切歌手势挂播放页，横向交给 Pager
    val trackSwipe = rememberHomeTrackSwipeGesture(
        playbackState = playbackState,
        sheetVisible = panelState.playlistVisible || panelState.audioInfoVisible,
    )

    // 弹层与曲库分析展开时占住面板，标题栏让位
    val sheetOnTop = panelState.playlistVisible || panelState.audioInfoVisible ||
        panelState.libraryAnalysis.visible
    // 手势协程只建一次，置顶状态须经 State 转交
    val sheetOnTopState = rememberUpdatedState(sheetOnTop)

    // 仅播放页自动收起，触摸唤出、2 秒收起；歌词拖拽例外
    val autoHideTopBar = panelState.currentPage == HomePage.PLAYER
    // 歌手页标题栏恒隐藏，也不随触摸唤出
    val topBarSuppressed = panelState.playlistImmersive
    var topBarVisible by remember { mutableStateOf(true) }
    // 唤出计数，自增以重置收起计时
    var topBarGeneration by remember { mutableIntStateOf(0) }
    // 记录触摸前是否收起，拖拽时不闪出标题栏
    var topBarHiddenBeforeTouch by remember { mutableStateOf(false) }
    // 拖拽接管期间标题栏始终收起
    var lyricDragClaimed by remember { mutableStateOf(false) }
    fun revealTopBar() {
        // 弹层置顶期间不唤出，避免标题栏反复进出
        if (sheetOnTopState.value) return
        topBarVisible = true
        topBarGeneration++
    }
    LaunchedEffect(autoHideTopBar, topBarVisible, topBarGeneration) {
        if (!autoHideTopBar || !topBarVisible) return@LaunchedEffect
        delay(TopBarAutoHideDelayMs)
        topBarVisible = false
    }
    // 切回播放页重新展示并重新计时
    LaunchedEffect(panelState.currentPage) {
        if (panelState.currentPage == HomePage.PLAYER) revealTopBar()
    }
    // 弹层与曲库分析展开时收起
    LaunchedEffect(sheetOnTop) {
        if (sheetOnTop) topBarVisible = false
    }
    val animatedTopBarAlpha by animateFloatAsState(
        targetValue = when {
            // 歌手页标题栏淡出收走，避免突起突没
            topBarSuppressed -> 0f
            topBarVisible || !autoHideTopBar -> 1f
            else -> 0f
        },
        animationSpec = tween(durationMillis = TopBarFadeDurationMs),
    )
    // 拖拽接管时直接归零，不留残影
    val topBarAlpha = if (lyricDragClaimed) 0f else animatedTopBarAlpha

    HomeShell(
        panelState = panelState,
        modifier = modifier,
        topBar = {
            HomeTopBar(
                playbackState = playbackState,
                isLiked = isLiked,
                favoriteEnabled = currentTrackId != null,
                onShowTimer = { panelState.showTimer = true },
                onToggleFavorite = { currentTrackId?.let { playbackState.toggleFavorite(it) } },
                onToggleLandscape = onToggleLandscape,
                onOpenSettings = onOpenSettings,
                // 不可见时不响应点击
                interactive = !topBarSuppressed && (topBarVisible || !autoHideTopBar),
                windowInsets = topBarWindowInsets,
                modifier = Modifier.graphicsLayer {
                    // 上移淡出并移出触摸范围，布局尺寸不变
                    alpha = topBarAlpha
                    translationY = -size.height * (1f - topBarAlpha)
                },
            )
        },
    ) { topInset ->
        HomePanels(
            panelState = panelState,
            topInset = topInset,
            onOpenSettings = onOpenSettings,
        ) {
            // 播放页铺满，封面嵌入标题栏后方
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(trackSwipe.modifier)
                    // Initial 阶段观察不消费，任意触摸唤出标题栏
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                // 先记录触摸前显隐，拖拽接管时据此收回
                                topBarHiddenBeforeTouch = !topBarVisible
                                lyricDragClaimed = false
                                revealTopBar()
                            }
                        }
                    },
            ) {
                PortraitPlayer(
                    modifier = Modifier.fillMaxSize(),
                    mediaAudioGranted = uiState.mediaAudioGranted,
                    topBarInset = topInset,
                    swipePreviewText = trackSwipe.previewText,
                    // 歌词快速滑动按切歌处理
                    onVerticalFling = trackSwipe::switchTrack,
                    // 拖拽接管时收回顺带唤出的标题栏
                    onLyricVerticalDragClaim = {
                        if (topBarHiddenBeforeTouch) {
                            lyricDragClaimed = true
                            topBarVisible = false
                        }
                    },
                    libraryAnalysis = panelState.libraryAnalysis,
                    playlistVisible = panelState.playlistVisible,
                    onPlaylistVisibilityChange = { panelState.playlistVisible = it },
                    // 播放按钮上滑唤出的音频信息弹窗
                    audioInfoVisible = panelState.audioInfoVisible,
                    onAudioInfoVisibilityChange = { panelState.audioInfoVisible = it },
                    // 菜单「在线搜索」：切到搜索页并自动搜
                    onOpenOnlineSearch = { query ->
                        playbackState.setSearchQuery(query)
                        playbackState.setSearchResultsVisible(true)
                        panelState.goToPage(HomePage.SEARCH)
                        scope.launch { performSearch(playbackState, context) }
                    },
                    // 点击歌手：切歌单面板并进该歌手
                    onOpenArtistPlaylist = panelState::openArtistPlaylist,
                    onOpenSpectrum = onOpenSpectrum,
                    onOpenMetadata = onOpenMetadata,
                )
            }
        }
        HomeDialogs(panelState = panelState)
    }
}
