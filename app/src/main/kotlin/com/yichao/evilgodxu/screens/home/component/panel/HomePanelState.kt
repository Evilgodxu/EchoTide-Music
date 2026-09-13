package com.yichao.evilgodxu.screens.home.component.panel

import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Stable
import androidx.compose.ui.platform.LocalContext
import com.yichao.evilgodxu.data.music.panel.MusicPanelStateHolder
import com.yichao.evilgodxu.screens.home.component.playlist.LibraryAnalysisController
import com.yichao.evilgodxu.theme.md_theme_dark_surface
import com.yichao.evilgodxu.LocalMusicPanelStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// 首页跨形态共享状态：在形态分派之上创建；旋转不重建 Activity，页面位置与后台分析需跨形态保持
@Stable
internal class HomePanelState(
    val playbackState: MusicPanelStateHolder,
    // 曲库分析会话：状态与后台任务常驻首页层，关闭对话框后分析继续执行
    val libraryAnalysis: LibraryAnalysisController,
    // 并列页面位置：页面切换统一由它驱动，页面自身不感知滑动过程
    val pagerState: PagerState,
    private val scope: CoroutineScope,
) {
    // 首页播放列表面板（底部弹出）显隐：显示期间让出纵向手势，滚动交由播放列表处理
    var playlistVisible by mutableStateOf(false)
    // 定时关闭对话框显隐
    var showTimer by mutableStateOf(false)
    // 播放速度对话框显隐
    var showSpeed by mutableStateOf(false)
    // 首页背景代表色：供搜索页与歌单页的浮层容器复用，保持与首页底色一致
    var backgroundColor by mutableStateOf(md_theme_dark_surface)

    // 当前落点页面
    val currentPage: HomePage
        get() = HomePage.entries[pagerState.currentPage]

    // 切到指定页面：位移与吸附动画由 Pager 结算
    fun goToPage(page: HomePage) {
        scope.launch { pagerState.animateScrollToPage(page.ordinal) }
    }

    // 收起两侧页面回到播放器
    fun closePanels() {
        goToPage(HomePage.PLAYER)
    }
}

@Composable
internal fun rememberHomePanelState(): HomePanelState {
    val context = LocalContext.current
    val playbackState = LocalMusicPanelStateHolder.current
    val scope = rememberCoroutineScope()
    // 默认落在播放器页，两侧页面仅在滑动或主动跳转时进入
    val pagerState = rememberPagerState(initialPage = HomePage.PLAYER.ordinal) {
        HomePage.entries.size
    }
    return remember(context, playbackState, pagerState) {
        HomePanelState(
            playbackState = playbackState,
            libraryAnalysis = LibraryAnalysisController(context, scope),
            pagerState = pagerState,
            scope = scope,
        )
    }
}
