package com.yichao.evilgodxu.screens.settings.level.storage

import com.yichao.evilgodxu.data.cache.CacheUsage

data class CacheUiState(
    // 各类缓存占用，进入与刷新时采样
    val usages: List<CacheUsage> = emptyList(),
    // 采样中，阻断重复采样
    val refreshing: Boolean = false,
    // 清理中，禁用入口
    val clearing: Boolean = false,
)
