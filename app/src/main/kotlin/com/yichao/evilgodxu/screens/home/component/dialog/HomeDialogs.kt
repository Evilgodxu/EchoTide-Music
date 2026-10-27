package com.yichao.evilgodxu.screens.home.component.dialog

import androidx.compose.runtime.Composable
import com.yichao.evilgodxu.screens.home.component.panel.HomePanelState
import com.yichao.evilgodxu.ui.component.dialog.TimerDialog

// 首页跨形态对话框：定时关闭
@Composable
internal fun HomeDialogs(
    panelState: HomePanelState,
) {
    val playbackState = panelState.playbackState.state
    TimerDialog(
        visible = panelState.showTimer,
        minutes = playbackState.timerMinutes,
        onMinutesChange = { playbackState.setTimerMinutes(it) },
        onConfirm = {
            playbackState.startTimer(playbackState.timerMinutes)
            panelState.showTimer = false
        },
        onCancel = { panelState.showTimer = false },
    )
}
