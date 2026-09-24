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
import com.yichao.evilgodxu.ui.component.SongImmersiveBackground

// 首页共享骨架：封面衍生沉浸背景 + 透明 Scaffold；形态差异由调用方通过插槽装配，骨架不承载手势
@Composable
internal fun HomeShell(
    panelState: HomePanelState,
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    content: @Composable BoxScope.(topInset: Dp) -> Unit,
) {
    Box(modifier = modifier.fillMaxSize()) {
        val playbackState = panelState.playbackState.state
        SongImmersiveBackground(
            track = playbackState.currentTrack,
            // 冷启动略缩图就绪前先用上次持久化的取色结果，避免首帧闪默认色
            restoredColors = playbackState.restoredGradientFor(playbackState.currentTrack),
            onBackgroundColor = { panelState.backgroundColor = it },
            onExtractedColors = { top, bottom -> playbackState.saveBackgroundGradient(top, bottom) },
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
