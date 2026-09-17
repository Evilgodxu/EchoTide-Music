package com.yichao.evilgodxu.screens.cache

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yichao.evilgodxu.data.cache.CacheInventory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 刷新提示行最短展示时长：需容下提示行展开动画，并留出足以看清的停留
private const val MIN_REFRESH_MS = 800L

class CacheViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(CacheUiState())
    val uiState: StateFlow<CacheUiState> = _uiState.asStateFlow()

    // 重新采样缓存占用：页面进入与下拉刷新共用此入口。
    // 采样期间保持 refreshing，既驱动下拉刷新指示器，也让进入页面时的自动刷新有可见反馈
    fun refresh() {
        if (_uiState.value.refreshing) return
        _uiState.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            try {
                val startedAt = SystemClock.elapsedRealtime()
                sampleUsage()
                // 目录采样多在毫秒级完成，须补足最短展示时长：否则 refreshing 在同帧内回落，
                // 提示行可能未及展开就被收起，用户得不到任何刷新反馈
                val remaining = MIN_REFRESH_MS - (SystemClock.elapsedRealtime() - startedAt)
                if (remaining > 0) delay(remaining)
            } finally {
                _uiState.update { it.copy(refreshing = false) }
            }
        }
    }

    // 清理应用自身缓存（整清 cacheDir + 异常日志/更新安装包），完成后重新采样使展示与实际一致。
    // 用户数据各自按保留策略回收，不在此列
    fun clearSystemCache() {
        if (_uiState.value.clearing) return
        _uiState.update { it.copy(clearing = true) }
        viewModelScope.launch {
            try {
                // 清理为阻塞 IO，统一切到 IO 线程，避免主线程磁盘写触达 StrictMode 惩罚
                withContext(Dispatchers.IO) { CacheInventory.clearSystemCache(getApplication()) }
                sampleUsage()
            } finally {
                _uiState.update { it.copy(clearing = false) }
            }
        }
    }

    // 采样各类缓存占用并回填状态：目录遍历为阻塞 IO，统一切到 IO 线程
    private suspend fun sampleUsage() {
        val usages = withContext(Dispatchers.IO) { CacheInventory.sample(getApplication()) }
        _uiState.update { it.copy(usages = usages) }
    }
}
