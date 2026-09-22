package com.yichao.evilgodxu.screens.home.component.panel

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.yichao.evilgodxu.screens.home.component.playlist.PlaylistPanel
import com.yichao.evilgodxu.screens.home.component.search.OnlineSearchPanel

// 首页并列页面容器：跟手拖动、吸附与回弹由系统 HorizontalPager 结算，页面自身不再参与手势
@Composable
internal fun HomePanels(
    panelState: HomePanelState,
    // 标题栏高度：搜索页与歌单页内容从标题栏下方开始
    topInset: Dp,
    modifier: Modifier = Modifier,
    // 播放器页：竖屏与横屏的播放器主体不同，由调用方按形态提供
    playerPage: @Composable () -> Unit,
) {
    val pagerState = panelState.pagerState
    HorizontalPager(
        state = pagerState,
        modifier = modifier.fillMaxSize(),
        // 底部播放列表面板是全屏遮罩式弹层：显示期间关闭翻页，面板内的横向滑动与屏幕边缘滑动都不会切页
        userScrollEnabled = !panelState.playlistVisible,
        // 三页均为重量级常驻视图（歌单页持有全库分组缓存与页面栈），全部保留在合成树，避免切页重建
        beyondViewportPageCount = HomePage.entries.size,
        key = { HomePage.entries[it] },
    ) { index ->
        when (HomePage.entries[index]) {
            HomePage.SEARCH -> OnlineSearchPanel(
                playbackState = panelState.playbackState.state,
                menuBackgroundColor = panelState.backgroundColor,
                // 三页常驻合成树，离页不等于离开合成：自动轮播等持续动效据此判定是否推进
                visible = panelState.currentPage == HomePage.SEARCH,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = topInset),
            )
            HomePage.PLAYER -> playerPage()
            HomePage.PLAYLIST -> PlaylistPanel(
                // 离开歌单页即视为关闭，页面内回退栈随之复位
                visible = panelState.currentPage == HomePage.PLAYLIST,
                playbackState = panelState.playbackState.state,
                menuBackgroundColor = panelState.backgroundColor,
                // 点击播放器歌手信息后请求打开的歌手歌单
                pendingArtist = panelState.pendingArtistPlaylist,
                onPendingArtistHandled = { panelState.pendingArtistPlaylist = null },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = topInset),
            )
        }
    }
}
