package com.yichao.evilgodxu.screens.settings.level.storage

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yichao.evilgodxu.LocalApplication

/** 存储层级：进入与回前台都重采一次 */
@Composable
internal fun CachePane(innerPadding: PaddingValues) {
    val application = LocalApplication.current
    val viewModel: CacheViewModel = viewModel(
        factory = viewModelFactory {
            initializer { CacheViewModel(application = application) }
        },
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refresh() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    CacheUsageGroups(
        usages = uiState.usages,
        clearing = uiState.clearing,
        refreshing = uiState.refreshing,
        onRefresh = viewModel::refresh,
        onClear = viewModel::clearSystemCache,
        innerPadding = innerPadding,
    )
}
