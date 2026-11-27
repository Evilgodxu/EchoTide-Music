package com.yichao.evilgodxu.screens.home.component.panel

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.yichao.evilgodxu.screens.home.component.playlist.PlaylistPanel
import com.yichao.evilgodxu.screens.home.component.search.OnlineSearchPanel

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
    HorizontalPager(
        state = pagerState,
        modifier = modifier.fillMaxSize(),
        // 面板展开、歌手页、下钻层都关闭翻页：一次滑动只对应一个去向
        userScrollEnabled = !panelState.playlistVisible &&
            !panelState.playlistImmersive &&
            !panelState.playlistSwipeBack,
        // 三页常驻合成树，避免切页重建
        beyondViewportPageCount = HomePage.entries.size,
        key = { HomePage.entries[it] },
    ) { index ->
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
