package com.yichao.evilgodxu.screens.home.component.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.yichao.evilgodxu.navigation.LocalScreenActive
import com.yichao.evilgodxu.screens.home.component.panel.HomePage
import com.yichao.evilgodxu.screens.home.component.panel.HomePanelState
import com.yichao.evilgodxu.ui.component.CoverFlowFrameStore
import com.yichao.evilgodxu.ui.component.SongImmersiveBackground

// 共享骨架：沉浸背景 + 透明 Scaffold，不承载手势
@Composable
internal fun HomeShell(
    panelState: HomePanelState,
    modifier: Modifier = Modifier,
    // 上层另有自绘背景时置 true：本层背景被完全盖住，不必再推帧
    backgroundSuppressed: Boolean = false,
    topBar: @Composable () -> Unit = {},
    content: @Composable BoxScope.(topInset: Dp) -> Unit,
) {
    Box(modifier = modifier.fillMaxSize()) {
        val playbackState = panelState.playbackState
        // 首页在场即代算背景帧，补后台时组合停摆，重复调用无副作用
        LaunchedEffect(Unit) { CoverFlowFrameStore.start(playbackState) }
        // 歌单页与搜索页铺不透明底色，背景帧只在播放页或翻页途中可见
        // 不可见时停摆，避免被底色盖住仍以 30fps 推相位
        val pagerState = panelState.pagerState
        val backgroundVisible = !backgroundSuppressed && (pagerState.isScrollInProgress ||
            panelState.currentPage == HomePage.PLAYER)
        val ownerActive = LocalScreenActive.current
        CompositionLocalProvider(
            LocalScreenActive provides (ownerActive && backgroundVisible),
        ) {
            SongImmersiveBackground(
                track = playbackState.currentTrack,
                // 换色与换图同向，类型取自曲目变更
                switchKind = playbackState.lastSwitchKind,
                onBackgroundColor = { panelState.backgroundColor = it },
                onExtractedColors = { colors -> playbackState.backgroundColors.saveForCurrentTrack(colors) },
            )
        }
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            topBar = topBar,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .consumeWindowInsets(innerPadding)
                    .clipToBounds(),
            ) {
                content(innerPadding.calculateTopPadding())
            }
        }
    }
}
