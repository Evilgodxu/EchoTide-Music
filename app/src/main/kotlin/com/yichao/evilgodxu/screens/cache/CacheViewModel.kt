package com.yichao.evilgodxu.screens.cache

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yichao.evilgodxu.data.cache.CacheInventory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CacheViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(CacheUiState())
    val uiState: StateFlow<CacheUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { loadUsage() }
    }

    // 清理可清理作用域，完成后重新采样使展示与实际一致。
    // 清理只覆盖 cacheDir，应用数据与用户数据各自按保留策略回收，不在此列
    fun clearSystemCache() {
        if (_uiState.value.clearing) return
        _uiState.update { it.copy(clearing = true) }
        viewModelScope.launch {
            try {
                CacheInventory.clearSystemCache(getApplication())
                loadUsage()
            } finally {
                _uiState.update { it.copy(clearing = false) }
            }
        }
    }

    // 采样各类缓存占用并回填状态：目录遍历为阻塞 IO，统一切到 IO 线程
    private suspend fun loadUsage() {
        val usages = withContext(Dispatchers.IO) { CacheInventory.sample(getApplication()) }
        _uiState.update { it.copy(usages = usages) }
    }
}
