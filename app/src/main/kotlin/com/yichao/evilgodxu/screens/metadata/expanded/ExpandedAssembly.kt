package com.yichao.evilgodxu.screens.metadata.expanded

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

// 宽屏下内容左右与底部留白：可视区充裕，让表单与屏幕边缘分离
private val EXPANDED_CONTENT_PADDING = 12.dp

// 宽屏组装器：常驻标题栏 + 留白内的纵向表单
@Composable
internal fun ExpandedAssembly(
    uiState: MetadataUiState,
    onBack: () -> Unit,
    onEditStart: (MetadataEditTarget) -> Unit,
    onEditEnd: () -> Unit,
    onTitleChange: (String) -> Unit,
    onArtistChange: (String) -> Unit,
    onAlbumChange: (String) -> Unit,
    onLyricRawChange: (String) -> Unit,
    onLyricTranslationChange: (Int, String) -> Unit,
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
            onLyricsWholeModeToggle = onLyricsWholeModeToggle,
            onCoverSelected = onCoverSelected,
            onCoverRemoved = onCoverRemoved,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(
                    start = EXPANDED_CONTENT_PADDING,
                    end = EXPANDED_CONTENT_PADDING,
                    bottom = EXPANDED_CONTENT_PADDING,
                ),
        )
    }
}
