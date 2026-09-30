package com.yichao.evilgodxu.screens.metadata

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
import com.yichao.evilgodxu.LocalMusicPanelStateHolder
import com.yichao.evilgodxu.screens.metadata.compact.CompactAssembly
import com.yichao.evilgodxu.screens.metadata.expanded.ExpandedAssembly
import com.yichao.evilgodxu.theme.StatusBarStyleEffect
import com.yichao.evilgodxu.windowsize.rememberExpandedForm

// 页面入口：形态分发 + 跨形态副作用，不承载布局
@Composable
fun MetadataScreen(
    trackId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val application = LocalApplication.current
    val stateHolder = LocalMusicPanelStateHolder.current
    val viewModel: MetadataViewModel = viewModel(
        // 按曲目区分实例：同一路由重复进入不同曲目时不复用上一次的表单状态
        key = "metadata-$trackId",
        factory = viewModelFactory {
            initializer {
                MetadataViewModel(
                    application = application,
                    stateHolder = stateHolder,
                    trackId = trackId,
                )
            }
        },
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // 状态栏图标跟随主题：浅色主题深色图标，深色主题白色图标
    StatusBarStyleEffect()

    // 每次进入页面都重读一次标签：ViewModel 会被导航栈缓存复用，只靠 init 会把上次的快照当作当前值。
    // 以组合是否处于前台为触发条件，从本页离开再回来、或从别处改过曲目后回来都能拿到磁盘上的最新内容
    LaunchedEffect(Unit) { viewModel.reload() }

    // 离开页面时立刻落盘尚未到点的改动：自动保存有停顿等待窗口，
    // 用户在窗口内退出不应丢掉最后一次输入
    DisposableEffect(viewModel) {
        onDispose { viewModel.flushPending() }
    }

    // 形态分派：旋转状态与窗口宽度尺寸类共同决定显示内容
    if (rememberExpandedForm()) {
        ExpandedAssembly(
            uiState = uiState,
            onBack = onBack,
            onEditStart = viewModel::onEditStart,
            onEditEnd = viewModel::onEditEnd,
            onTitleChange = viewModel::onTitleChange,
            onArtistChange = viewModel::onArtistChange,
            onAlbumChange = viewModel::onAlbumChange,
            onLyricLineChange = viewModel::onLyricLineChange,
            onCoverSelected = viewModel::onCoverSelected,
            onCoverRemoved = viewModel::onCoverRemoved,
            modifier = modifier,
        )
    } else {
        CompactAssembly(
            uiState = uiState,
            onBack = onBack,
            onEditStart = viewModel::onEditStart,
            onEditEnd = viewModel::onEditEnd,
            onTitleChange = viewModel::onTitleChange,
            onArtistChange = viewModel::onArtistChange,
            onAlbumChange = viewModel::onAlbumChange,
            onLyricLineChange = viewModel::onLyricLineChange,
            onCoverSelected = viewModel::onCoverSelected,
            onCoverRemoved = viewModel::onCoverRemoved,
            modifier = modifier,
        )
    }
}
