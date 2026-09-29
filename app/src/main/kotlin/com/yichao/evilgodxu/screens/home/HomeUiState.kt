package com.yichao.evilgodxu.screens.home

// 首页 UI 状态
data class HomeUiState(
    val isLoading: Boolean = false,
    val allFilesGranted: Boolean = false,
    val mediaAudioGranted: Boolean = false,
    val mediaImageGranted: Boolean = false,
    val notificationGranted: Boolean = false,
) {
    // 全部权限已授权时隐藏权限状态分区。
    // 通知权限刻意不计入：仅缺通知时对话框应正常关闭，否则用户拒绝通知会永久锁死首页
    val allPermissionsGranted: Boolean
        get() = allFilesGranted && mediaAudioGranted && mediaImageGranted
}
