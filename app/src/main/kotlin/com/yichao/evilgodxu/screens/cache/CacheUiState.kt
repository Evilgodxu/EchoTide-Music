package com.yichao.evilgodxu.screens.cache

import com.yichao.evilgodxu.data.cache.CacheUsage

data class CacheUiState(
    // 各类缓存的产出占用：进入页面时采样一次，清理完成后重新采样
    val usages: List<CacheUsage> = emptyList(),
    // 清理进行中：期间禁用清理入口，避免重复触发
    val clearing: Boolean = false,
)
