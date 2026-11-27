package com.yichao.evilgodxu.screens.settings.level.storage

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

// 刷新提示最短展示 800ms
private const val MIN_REFRESH_MS = 800L

class CacheViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(CacheUiState())
    val uiState: StateFlow<CacheUiState> = _uiState.asStateFlow()

    // 重采样：进入与下拉刷新共用
    fun refresh() {
        if (_uiState.value.refreshing) return
        _uiState.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            try {
                val startedAt = SystemClock.elapsedRealtime()
                sampleUsage()
                // 补足最短时长，避免提示未展开即收起
                val remaining = MIN_REFRESH_MS - (SystemClock.elapsedRealtime() - startedAt)
                if (remaining > 0) delay(remaining)
            } finally {
                _uiState.update { it.copy(refreshing = false) }
            }
        }
    }

    // 清 cacheDir 与日志/安装包，不含用户数据
    fun clearSystemCache() {
        if (_uiState.value.clearing) return
        _uiState.update { it.copy(clearing = true) }
        viewModelScope.launch {
            try {
                // 阻塞 IO 切到 IO 线程
                withContext(Dispatchers.IO) { CacheInventory.clearSystemCache(getApplication()) }
                sampleUsage()
            } finally {
                _uiState.update { it.copy(clearing = false) }
            }
        }
    }

    // 目录遍历切到 IO 线程
    private suspend fun sampleUsage() {
        val usages = withContext(Dispatchers.IO) { CacheInventory.sample(getApplication()) }
        _uiState.update { it.copy(usages = usages) }
    }
}
