package com.yichao.evilgodxu.screens.home

// 首页 UI 状态
data class HomeUiState(
    val isLoading: Boolean = false,
    val allFilesGranted: Boolean = false,
    val mediaAudioGranted: Boolean = false,
    val mediaImageGranted: Boolean = false,
    val bluetoothConnectGranted: Boolean = false,
    val notificationGranted: Boolean = false,
) {
    // 全部权限已授权时隐藏权限状态分区。
    // 通知与蓝牙权限刻意不计入：两者只影响局部能力（通知展示、蓝牙设备名），
    // 缺失时对话框应正常关闭，否则用户拒绝其一就会永久锁死首页
    val allPermissionsGranted: Boolean
        get() = allFilesGranted && mediaAudioGranted && mediaImageGranted
}
