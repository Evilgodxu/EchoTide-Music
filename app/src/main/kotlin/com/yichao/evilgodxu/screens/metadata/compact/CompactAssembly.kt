package com.yichao.evilgodxu.screens.metadata.compact

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.screens.metadata.MetadataEditTarget
import com.yichao.evilgodxu.screens.metadata.MetadataUiState
import com.yichao.evilgodxu.screens.metadata.component.MetadataForm
import com.yichao.evilgodxu.ui.component.PageTopBar

// 窄屏下内容左右留白
private val COMPACT_HORIZONTAL_PADDING = 16.dp

// 窄屏组装器：常驻标题栏 + 纵向滚动的元数据表单
@Composable
internal fun CompactAssembly(
    uiState: MetadataUiState,
    onBack: () -> Unit,
    onEditStart: (MetadataEditTarget) -> Unit,
    onEditEnd: () -> Unit,
    onTitleChange: (String) -> Unit,
    onArtistChange: (String) -> Unit,
    onAlbumChange: (String) -> Unit,
    onLyricRawChange: (String) -> Unit,
    onLyricTranslationChange: (Int, String) -> Unit,
    onLyricLineTranslate: (Int) -> Unit,
    onLyricsWholeModeToggle: () -> Unit,
    onCoverSelected: (ByteArray) -> Unit,
    onCoverRemoved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            PageTopBar(title = stringResource(R.string.metadata_screen_title), onBack = onBack)
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        MetadataForm(
            uiState = uiState,
            onEditStart = onEditStart,
            onEditEnd = onEditEnd,
            onTitleChange = onTitleChange,
            onArtistChange = onArtistChange,
            onAlbumChange = onAlbumChange,
            onLyricRawChange = onLyricRawChange,
            onLyricTranslationChange = onLyricTranslationChange,
            onLyricLineTranslate = onLyricLineTranslate,
            onLyricsWholeModeToggle = onLyricsWholeModeToggle,
            onCoverSelected = onCoverSelected,
            onCoverRemoved = onCoverRemoved,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                // 只留左右白边，底缘不留固定内边距：它在滚动容器之外，键盘弹出时会变成
                // 键盘上沿一条不随内容滚动的页面底色空隙
                .padding(horizontal = COMPACT_HORIZONTAL_PADDING),
        )
    }
}
