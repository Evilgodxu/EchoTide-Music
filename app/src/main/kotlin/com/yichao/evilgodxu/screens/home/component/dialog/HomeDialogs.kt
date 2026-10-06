package com.yichao.evilgodxu.screens.home.component.dialog

import android.app.Activity
import androidx.compose.runtime.Composable
import com.yichao.evilgodxu.permission.PermissionType
import com.yichao.evilgodxu.screens.home.component.panel.HomePanelState
import com.yichao.evilgodxu.screens.home.component.permission.PermissionDialog
import com.yichao.evilgodxu.screens.home.HomeUiState
import com.yichao.evilgodxu.ui.component.dialog.TimerDialog

// 首页跨形态对话框：权限申请与定时关闭
@Composable
internal fun HomeDialogs(
    panelState: HomePanelState,
    uiState: HomeUiState,
    onRefreshPermissions: () -> Unit,
    onStartPermissionMonitor: (PermissionType, Activity) -> Unit,
    onStopPermissionMonitor: () -> Unit,
) {
    val playbackState = panelState.playbackState.state
    PermissionDialog(
        uiState = uiState,
        onRefresh = onRefreshPermissions,
        onStartPermissionMonitor = onStartPermissionMonitor,
        onStopPermissionMonitor = onStopPermissionMonitor,
    )
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
