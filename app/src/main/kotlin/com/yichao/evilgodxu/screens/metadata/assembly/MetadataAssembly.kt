package com.yichao.evilgodxu.screens.metadata.assembly

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yichao.evilgodxu.LocalPlaybackState
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.data.music.metadata.TagField
import com.yichao.evilgodxu.data.music.model.MusicSearchSource
import com.yichao.evilgodxu.data.music.model.NeteaseSongSearchResult
import com.yichao.evilgodxu.screens.metadata.MetadataEditTarget
import com.yichao.evilgodxu.screens.metadata.MetadataUiState
import com.yichao.evilgodxu.screens.metadata.component.MetadataForm
import com.yichao.evilgodxu.ui.component.PageTopBar
import com.yichao.evilgodxu.windowsize.WindowSizeClass

// 内容左右留白：窄窗空间紧张按 16dp 留足触控边距，宽窗可视区充裕，
// 收到 12dp 让表单与屏幕边缘分离而不显空
private val COMPACT_HORIZONTAL_PADDING = 16.dp
private val EXPANDED_HORIZONTAL_PADDING = 12.dp

// 元数据页组装器：骨架为 Scaffold + 纵向滚动的表单，形态差异只体现在左右留白
@Composable
internal fun MetadataAssembly(
    windowSizeClass: WindowSizeClass,
    uiState: MetadataUiState,
    onBack: () -> Unit,
    onEditStart: (MetadataEditTarget) -> Unit,
    onEditEnd: () -> Unit,
    onFieldChange: (TagField, String) -> Unit,
    onLyricRawChange: (String) -> Unit,
    onLyricTranslationChange: (Int, String) -> Unit,
    onLyricLineTranslate: (Int) -> Unit,
    onLyricsWholeModeToggle: () -> Unit,
    onCoverSelected: (ByteArray) -> Unit,
    onCoverRemoved: () -> Unit,
    onCoverExport: () -> Unit,
    onSameAlbumRequest: () -> Unit,
    onSameAlbumDismiss: () -> Unit,
    onCoverRefreshOpen: () -> Unit,
    onCoverRefreshSourceSelected: (MusicSearchSource) -> Unit,
    onCoverRefreshReload: () -> Unit,
    onCoverRefreshCandidateSelected: (NeteaseSongSearchResult) -> Unit,
    onCoverRefreshConfirm: () -> Unit,
    onCoverRefreshCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val horizontalPadding = if (windowSizeClass == WindowSizeClass.Compact) {
        COMPACT_HORIZONTAL_PADDING
    } else {
        EXPANDED_HORIZONTAL_PADDING
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            PageTopBar(title = stringResource(R.string.metadata_screen_title), onBack = onBack)
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        MetadataForm(
            uiState = uiState,
            playbackState = LocalPlaybackState.current,
            onEditStart = onEditStart,
            onEditEnd = onEditEnd,
            onFieldChange = onFieldChange,
            onLyricRawChange = onLyricRawChange,
            onLyricTranslationChange = onLyricTranslationChange,
            onLyricLineTranslate = onLyricLineTranslate,
            onLyricsWholeModeToggle = onLyricsWholeModeToggle,
            onCoverSelected = onCoverSelected,
            onCoverRemoved = onCoverRemoved,
            onCoverExport = onCoverExport,
            onSameAlbumRequest = onSameAlbumRequest,
            onSameAlbumDismiss = onSameAlbumDismiss,
            onCoverRefreshOpen = onCoverRefreshOpen,
            onCoverRefreshSourceSelected = onCoverRefreshSourceSelected,
            onCoverRefreshReload = onCoverRefreshReload,
            onCoverRefreshCandidateSelected = onCoverRefreshCandidateSelected,
            onCoverRefreshConfirm = onCoverRefreshConfirm,
            onCoverRefreshCancel = onCoverRefreshCancel,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                // 只留左右白边，底缘不留固定内边距：它在滚动容器之外，键盘弹出时会变成
                // 键盘上沿一条不随内容滚动的页面底色空隙
                .padding(horizontal = horizontalPadding),
        )
    }
}
