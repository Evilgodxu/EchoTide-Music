package com.yichao.evilgodxu.screens.home

data class HomeUiState(
    // 唯一启动前置权限：缺它读不到曲目
    val mediaAudioGranted: Boolean = false,
) {
    // 缺音频权限首页不可用，提示不可关闭
    val blockingPermissionsGranted: Boolean
        get() = mediaAudioGranted
}
