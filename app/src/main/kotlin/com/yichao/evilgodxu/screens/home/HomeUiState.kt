package com.yichao.evilgodxu.screens.home

data class HomeUiState(
    // 首启动唯一的权限前置：缺它读不到任何曲目，其余权限都在各自触发点按需申请
    val mediaAudioGranted: Boolean = false,
) {
    // 音频访问缺失时首页无法工作，权限提示因此不可关闭
    val blockingPermissionsGranted: Boolean
        get() = mediaAudioGranted
}
