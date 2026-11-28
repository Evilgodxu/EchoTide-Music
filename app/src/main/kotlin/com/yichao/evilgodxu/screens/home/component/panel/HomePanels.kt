package com.yichao.evilgodxu.screens.home.component.panel

import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.yichao.evilgodxu.navigation.LocalScreenActive
import com.yichao.evilgodxu.screens.home.component.playlist.PlaylistPanel
import com.yichao.evilgodxu.screens.home.component.search.OnlineSearchPanel
import com.yichao.evilgodxu.theme.AppMotion

// 并列页面，拖动吸附由 Pager 结算
@Composable
internal fun HomePanels(
    panelState: HomePanelState,
    // 标题栏高度：搜索与歌单页内容下移（歌手页除外）
    topInset: Dp,
    // 歌单总览页继续左滑时的去向：打开设置页
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    // 播放器页，主体由调用方按形态提供
    playerPage: @Composable () -> Unit,
) {
    val pagerState = panelState.pagerState
    // 覆盖层压住时已整体不活跃；此处再按翻页收窄，同一时刻只让一页推帧
    val ownerActive = LocalScreenActive.current
    val scrolling = pagerState.isScrollInProgress
    val currentPage = pagerState.currentPage
    // 切页意图在此执行：意图留在状态里，动画被打断或 Pager 重建后仍会按目标落位
    val pageRequest = panelState.pageRequest
    LaunchedEffect(pagerState, pageRequest) {
        val target = pageRequest ?: return@LaunchedEffect
        pagerState.animateScrollToPage(
            page = target.ordinal,
            animationSpec = tween(AppMotion.Standard),
        )
        // 动画被接手时停在中间页，直接落位保证页面与意图一致
        if (pagerState.currentPage != target.ordinal) {
            pagerState.scrollToPage(target.ordinal)
        }
        panelState.onPageRequestDone(target)
    }
    HorizontalPager(
        state = pagerState,
        modifier = modifier.fillMaxSize(),
        // 面板展开、歌手页、下钻层与切页转场期间关闭翻页：一次滑动只对应一个去向
        userScrollEnabled = pageRequest == null &&
            !panelState.playlistVisible &&
            !panelState.playlistImmersive &&
            !panelState.playlistSwipeBack,
        // 三页常驻合成树，避免切页重建
        beyondViewportPageCount = HomePage.entries.size,
        key = { HomePage.entries[it] },
    ) { index ->
        // 滑动中相邻页可见，两页都保持活跃；落定后只留当前页
        val pageActive = scrolling || index == currentPage
        CompositionLocalProvider(LocalScreenActive provides (ownerActive && pageActive)) {
            when (HomePage.entries[index]) {
                HomePage.SEARCH -> OnlineSearchPanel(
                    playbackState = panelState.playbackState,
                    menuBackgroundColor = panelState.backgroundColor,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = topInset),
                )

                HomePage.PLAYER -> playerPage()
                HomePage.PLAYLIST -> PlaylistPanel(
                    // 离开歌单页即关闭，回退栈复位
                    visible = panelState.currentPage == HomePage.PLAYLIST,
                    playbackState = panelState.playbackState,
                    menuBackgroundColor = panelState.backgroundColor,
                    // 顶部内边距由面板分配，沉浸头图铺到标题栏后方
                    topInset = topInset,
                    // 歌手页在场时让出标题栏
                    onImmersivePageChange = { panelState.playlistImmersive = it },
                    // 下钻层接管横滑，首页翻页让位
                    onSwipeBackChange = { panelState.playlistSwipeBack = it },
                    // 歌手页回退直接回播放器
                    onClose = { panelState.closePanels() },
                    // 歌单总览页继续左滑进入设置页
                    onOpenSettings = onOpenSettings,
                    // 点击播放器歌手信息后请求打开的歌手歌单
                    pendingArtist = panelState.pendingArtistPlaylist,
                    onPendingArtistHandled = { panelState.pendingArtistPlaylist = null },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
