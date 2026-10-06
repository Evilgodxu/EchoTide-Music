package com.yichao.evilgodxu.screens.home

// 首页 UI 状态
data class HomeUiState(
    val isLoading: Boolean = false,
    val allFilesGranted: Boolean = false,
    val mediaAudioGranted: Boolean = false,
    val mediaImageGranted: Boolean = false,
    val bluetoothConnectGranted: Boolean = false,
    val notificationGranted: Boolean = false,
    val batteryWhitelistGranted: Boolean = false,
    // 免打扰访问：仅 USB 独占聆听时用得到，故不计入 allPermissionsSatisfied——它的权限行只在独占开关
    // 开启时列出，计入会让对话框在无行可显示的情况下被这条权限一直挂住
    val notificationPolicyGranted: Boolean = false,
) {
    // 阻塞式核心权限：缺失时首页无法工作，权限对话框因此不可关闭
    val blockingPermissionsGranted: Boolean
        get() = allFilesGranted && mediaAudioGranted && mediaImageGranted

    // 任一权限缺失都要以对话框形式呈现，启动时不直接弹系统权限窗。
    // 蓝牙、通知与电池优化白名单只影响局部能力（设备名、通知展示、熄屏后台播放），
    // 不计入 blockingPermissionsGranted：用户拒绝其一时对话框仍可关闭，否则首页会被永久占用
    val allPermissionsSatisfied: Boolean
        get() = blockingPermissionsGranted && bluetoothConnectGranted &&
            notificationGranted && batteryWhitelistGranted
}
