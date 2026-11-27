package com.yichao.evilgodxu.screens.home.component.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.yichao.evilgodxu.screens.home.component.panel.HomePanelState
import com.yichao.evilgodxu.ui.component.CoverFlowFrameStore
import com.yichao.evilgodxu.ui.component.SongImmersiveBackground

// 共享骨架：沉浸背景 + 透明 Scaffold，不承载手势
@Composable
internal fun HomeShell(
    panelState: HomePanelState,
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    content: @Composable BoxScope.(topInset: Dp) -> Unit,
) {
    Box(modifier = modifier.fillMaxSize()) {
        val playbackState = panelState.playbackState
        // 首页在场即代算背景帧，补后台时组合停摆，重复调用无副作用
        LaunchedEffect(Unit) { CoverFlowFrameStore.start(playbackState) }
        SongImmersiveBackground(
            track = playbackState.currentTrack,
            // 换色与换图同向，类型取自曲目变更
            switchKind = playbackState.lastSwitchKind,
            onBackgroundColor = { panelState.backgroundColor = it },
            onExtractedColors = { colors -> playbackState.backgroundColors.saveForCurrentTrack(colors) },
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
