package com.yichao.evilgodxu.screens.home.component.panel

import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Stable
import androidx.compose.ui.platform.LocalContext
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.screens.home.component.analysis.LibraryAnalysisController
import com.yichao.evilgodxu.theme.AppMotion
import com.yichao.evilgodxu.theme.md_theme_dark_surface
import com.yichao.evilgodxu.LocalPlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// 跨形态共享状态：旋转不重建 Activity，页面位置需保持
@Stable
internal class HomePanelState(
    val playbackState: MusicPlaybackState,
    // 曲库分析对话框显隐，关闭仅收起
    val libraryAnalysis: LibraryAnalysisController,
    // 并列页面位置，页面本身不感知滑动
    val pagerState: PagerState,
    private val scope: CoroutineScope,
) {
    // 播放列表面板显隐，显示时让出纵向手势
    var playlistVisible by mutableStateOf(false)
    // 音频信息弹窗显隐，同样让出纵向手势
    var audioInfoVisible by mutableStateOf(false)
    var showTimer by mutableStateOf(false)
    // 背景代表色，供搜索与歌单浮层复用
    var backgroundColor by mutableStateOf(md_theme_dark_surface)
    // 待打开的歌手歌单，由歌单面板消费
    var pendingArtistPlaylist by mutableStateOf<String?>(null)
    // 停留在歌手页：标题栏隐藏；须在同一次回调写入，否则首帧闪出
    var playlistImmersive by mutableStateOf(false)
    // 下钻页：右滑回退并关闭首页翻页（仍显示标题栏）
    var playlistSwipeBack by mutableStateOf(false)

    val currentPage: HomePage
        get() = HomePage.entries[pagerState.currentPage]

    // 切到指定页面：位移与吸附动画由 Pager 结算
    // 显式给缓动与时长，默认弹簧在整宽位移上起步过猛，看起来像瞬移
    fun goToPage(page: HomePage) {
        scope.launch {
            pagerState.animateScrollToPage(
                page = page.ordinal,
                animationSpec = tween(AppMotion.Standard),
            )
        }
    }

    fun closePanels() {
        goToPage(HomePage.PLAYER)
    }

    // 打开歌手歌单：切到歌单面板并跳转
    // 直达歌手页时 Pager 直接落位，只留面板内那一层滑入；
    // 两段整宽位移同向叠加会让转场看起来快一倍
    fun openArtistPlaylist(artist: String) {
        pendingArtistPlaylist = artist
        scope.launch { pagerState.scrollToPage(HomePage.PLAYLIST.ordinal) }
    }
}

@Composable
internal fun rememberHomePanelState(): HomePanelState {
    val context = LocalContext.current
    val playbackState = LocalPlaybackState.current
    val scope = rememberCoroutineScope()
    // 默认播放器页，两侧页靠滑动进入
    val pagerState = rememberPagerState(initialPage = HomePage.PLAYER.ordinal) {
        HomePage.entries.size
    }
    return remember(context, playbackState, pagerState) {
        HomePanelState(
            playbackState = playbackState,
            libraryAnalysis = LibraryAnalysisController(),
            pagerState = pagerState,
            scope = scope,
        )
    }
}
