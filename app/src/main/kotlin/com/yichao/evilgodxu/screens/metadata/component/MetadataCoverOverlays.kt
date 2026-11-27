package com.yichao.evilgodxu.screens.metadata.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.data.music.model.MusicSearchSource
import com.yichao.evilgodxu.data.music.model.NeteaseSongSearchResult
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.screens.metadata.MetadataUiState
import com.yichao.evilgodxu.ui.component.DialogCard
import com.yichao.evilgodxu.ui.component.player.CoverRefreshDialog

/** 封面浮层：同专辑选择与在线刷新候选 */
@Composable
internal fun MetadataCoverOverlays(
    uiState: MetadataUiState,
    playbackState: MusicPlaybackState,
    onSameAlbumDismiss: () -> Unit,
    onSameAlbumSelected: (ByteArray) -> Unit,
    onCoverRefreshSourceSelected: (MusicSearchSource) -> Unit,
    onCoverRefreshReload: () -> Unit,
    onCoverRefreshCandidateSelected: (NeteaseSongSearchResult) -> Unit,
    onCoverRefreshConfirm: () -> Unit,
    onCoverRefreshCancel: () -> Unit,
) {
    SameAlbumCoverPicker(
        visible = uiState.sameAlbumVisible,
        loading = uiState.sameAlbumLoading,
        covers = uiState.sameAlbumCovers,
        onDismiss = onSameAlbumDismiss,
        onSelected = onSameAlbumSelected,
    )
    // 目标曲目须是本页正在编辑的那首
    val track = playbackState.playlist.firstOrNull { it.id == uiState.trackId }
        ?: playbackState.libraryTracks.firstOrNull { it.id == uiState.trackId }
    CoverRefreshDialog(
        visible = uiState.coverRefreshVisible,
        track = track,
        playbackState = playbackState,
        context = LocalContext.current,
        selectedId = uiState.coverRefreshSelectedId,
        saving = uiState.coverRefreshSaving,
        onCandidateSelected = onCoverRefreshCandidateSelected,
        onSourceSelected = onCoverRefreshSourceSelected,
        onRefresh = onCoverRefreshReload,
        onConfirm = onCoverRefreshConfirm,
        onCancel = onCoverRefreshCancel,
    )
}

// 同专辑封面：横向候选，选中即回填
@Composable
private fun SameAlbumCoverPicker(
    visible: Boolean,
    loading: Boolean,
    covers: List<ByteArray>,
    onDismiss: () -> Unit,
    onSelected: (ByteArray) -> Unit,
) {
    if (!visible) return
    DialogCard(onDismiss = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.metadata_cover_same_album_title),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
            )
            Box(
                modifier = Modifier.fillMaxWidth().height(96.dp),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    loading -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        Text(
                            text = stringResource(R.string.metadata_cover_same_album_loading),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                        )
                    }
                    covers.isEmpty() -> Text(
                        text = stringResource(R.string.metadata_cover_same_album_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                    )
                    else -> LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    ) {
                        items(covers) { bytes ->
                            SameAlbumCoverItem(bytes = bytes, onClick = { onSelected(bytes) })
                        }
                    }
                }
            }
        }
    }
}
