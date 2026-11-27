package com.yichao.evilgodxu.screens.metadata

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yichao.evilgodxu.LocalApplication
import com.yichao.evilgodxu.LocalPlaybackState
import com.yichao.evilgodxu.screens.metadata.assembly.MetadataAssembly
import com.yichao.evilgodxu.screens.metadata.component.MetadataPermissionRequest
import com.yichao.evilgodxu.theme.StatusBarStyleEffect
import com.yichao.evilgodxu.windowsize.rememberWindowSizeClass

// 页面入口：分发与副作用，不承载布局
@Composable
fun MetadataScreen(
    trackId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val application = LocalApplication.current
    val playbackState = LocalPlaybackState.current
    val viewModel: MetadataViewModel = viewModel(
        // 按曲目区分实例，避免复用上次表单
        key = "metadata-$trackId",
        factory = viewModelFactory {
            initializer {
                MetadataViewModel(
                    application = application,
                    playbackState = playbackState,
                    trackId = trackId,
                )
            }
        },
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // 状态栏图标跟随主题
    StatusBarStyleEffect()

    // 每次进入重读标签，ViewModel 会被缓存复用
    LaunchedEffect(Unit) { viewModel.reload() }

    // 进入即申请所需权限
    MetadataPermissionRequest()

    // 离开时立即落盘未到点的改动
    DisposableEffect(viewModel) {
        onDispose { viewModel.flushPending() }
    }

    // 返回两步：编辑态先退出编辑，再返回才离开页面
    val handleBack: () -> Unit = {
        if (uiState.editing != null) viewModel.onEditEnd() else onBack()
    }
    // 仅编辑态拦截返回键
    BackHandler(enabled = uiState.editing != null, onBack = handleBack)

    // 尺寸类在此计算后下传
    MetadataAssembly(
        windowSizeClass = rememberWindowSizeClass(),
        uiState = uiState,
        onBack = handleBack,
        onEditStart = viewModel::onEditStart,
        onEditEnd = viewModel::onEditEnd,
        onFieldChange = viewModel::onFieldChange,
        onLyricRawChange = viewModel::onLyricRawChange,
        onLyricTranslationChange = viewModel::onLyricTranslationChange,
        onLyricLineTranslate = viewModel::onLyricLineTranslate,
        onLyricsWholeModeToggle = viewModel::onLyricsWholeModeToggle,
        onCoverSelected = viewModel::onCoverSelected,
        onCoverRemoved = viewModel::onCoverRemoved,
        onCoverExport = viewModel::onCoverExport,
        onSameAlbumRequest = viewModel::onSameAlbumCoversRequest,
        onSameAlbumDismiss = viewModel::onSameAlbumCoversDismiss,
        onCoverRefreshOpen = viewModel::onCoverRefreshOpen,
        onCoverRefreshSourceSelected = viewModel::onCoverRefreshSourceSelected,
        onCoverRefreshReload = viewModel::onCoverRefreshReload,
        onCoverRefreshCandidateSelected = viewModel::onCoverRefreshCandidateSelected,
        onCoverRefreshConfirm = viewModel::onCoverRefreshConfirm,
        onCoverRefreshCancel = viewModel::onCoverRefreshCancel,
        onFillOpen = viewModel::onFillOpen,
        onFillSourceSelected = viewModel::onFillSourceSelected,
        onFillReload = viewModel::onFillReload,
        onFillCandidateSelected = viewModel::onFillCandidateSelected,
        onFillOverwriteChange = viewModel::onFillOverwriteChange,
        onFillConfirm = viewModel::onFillConfirm,
        onFillApply = viewModel::onFillApply,
        onFillModeDismiss = viewModel::onFillModeDismiss,
        onFillCancel = viewModel::onFillCancel,
        onReplayGainCompute = viewModel::onReplayGainCompute,
        modifier = modifier,
    )
}
