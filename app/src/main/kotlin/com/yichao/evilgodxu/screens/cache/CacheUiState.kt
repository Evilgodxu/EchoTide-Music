package com.yichao.evilgodxu.screens.cache

import com.yichao.evilgodxu.data.cache.CacheUsage

data class CacheUiState(
    // 各类缓存的产出占用：进入页面与下拉刷新时采样，清理完成后重新采样
    val usages: List<CacheUsage> = emptyList(),
    // 采样进行中：驱动下拉刷新指示器，并阻断重复采样
    val refreshing: Boolean = false,
    // 清理进行中：期间禁用清理入口，避免重复触发
    val clearing: Boolean = false,
)
