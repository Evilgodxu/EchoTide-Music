package com.yichao.evilgodxu.screens.home.component.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.yichao.evilgodxu.screens.home.component.panel.HomePanelState
import com.yichao.evilgodxu.ui.component.SongGradientBackground

// 首页共享骨架：沉浸式渐变背景 + 透明 Scaffold；形态差异由调用方通过插槽装配，骨架不承载手势
@Composable
internal fun HomeShell(
    panelState: HomePanelState,
    // 顶部状态栏区域是否压暗：竖屏需要，横屏系统栏隐藏时不需要
    darkenStatusBarArea: Boolean,
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    content: @Composable BoxScope.(topInset: Dp) -> Unit,
) {
    Box(modifier = modifier.fillMaxSize()) {
        SongGradientBackground(
            track = panelState.playbackState.state.currentTrack,
            darkenStatusBarArea = darkenStatusBarArea,
            onBackgroundColor = { panelState.backgroundColor = it },
        )
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
