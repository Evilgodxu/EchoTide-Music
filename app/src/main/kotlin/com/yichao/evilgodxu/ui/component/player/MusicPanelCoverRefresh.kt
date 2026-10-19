package com.yichao.evilgodxu.ui.component.player

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.yichao.evilgodxu.data.music.model.MusicSearchSource
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.model.NeteaseSongSearchResult
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.data.music.proxy.OnlinePlatformRegistry
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.ui.component.DialogCard
import com.yichao.evilgodxu.ui.component.coverThumbnailSize

// 换封面预览的封面显示边长
private val COVER_PREVIEW_DP = 96.dp

@Composable
internal fun CoverRefreshDialog(
    visible: Boolean,
    track: MusicTrack?,
    playbackState: MusicPlaybackState,
    context: Context,
    selectedId: Long?,
    saving: Boolean,
    onCandidateSelected: (NeteaseSongSearchResult) -> Unit,
    onSourceSelected: (MusicSearchSource) -> Unit,
    onRefresh: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    RefreshCandidateDialog(
        visible = visible && track != null,
        searching = playbackState.isCoverSearching || saving,
        saving = saving,
        candidates = playbackState.coverCandidates,
        selectedId = selectedId,
        source = playbackState.coverRefreshSource,
        titleText = OnlinePlatformRegistry.displayName(context, playbackState.coverRefreshSource),
        refreshLabel = stringResource(R.string.music_panel_refresh_cover),
        noCandidatesText = stringResource(R.string.music_panel_cover_no_candidates),
        onSourceSelected = onSourceSelected,
        onRefresh = onRefresh,
        onCandidateSelected = onCandidateSelected,
        onConfirm = onConfirm,
        onCancel = onCancel,
        candidateItem = { candidate, selected ->
            CoverCandidateItem(candidate = candidate, selected = selected, onSelected = onCandidateSelected)
        },
    )
}

// 封面候选条目：封面图 + 标题 + 歌手（内容形态与歌词条目不同，故注入）
@Composable
private fun CoverCandidateItem(
    candidate: NeteaseSongSearchResult,
    selected: Boolean,
    onSelected: (NeteaseSongSearchResult) -> Unit,
) {
    Column(
        modifier = Modifier.width(92.dp).clickable { onSelected(candidate) },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            AsyncImage(
                model = candidate.coverUrl,
                contentDescription = candidate.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(84.dp).clip(RoundedCornerShape(8.dp))
            )
        }
        Spacer(modifier = Modifier.height(1.dp))
        Text(
            text = candidate.title,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 11.sp,
            lineHeight = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = candidate.artist,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun CoverReplaceDialog(
    visible: Boolean,
    track: MusicTrack?,
    candidate: NeteaseSongSearchResult?,
    saving: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    if (visible && track != null && candidate != null) {
        DialogCard(onDismiss = onCancel) {
            CoverReplaceContent(
                track = track,
                candidate = candidate,
                saving = saving,
                onConfirm = onConfirm,
                onCancel = onCancel,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun CoverReplaceContent(
    track: MusicTrack,
    candidate: NeteaseSongSearchResult,
    saving: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(stringResource(R.string.music_panel_cover_replace_title), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(vertical = 18.dp)) {
            AlbumArt(
                track = track,
                modifier = Modifier.size(COVER_PREVIEW_DP).clip(RoundedCornerShape(10.dp)),
                // 预览封面不大，按控件实际渲染尺寸换算请求档位，不沿用轮播的 512 大档
                thumbnailSize = coverThumbnailSize(LocalDensity.current, COVER_PREVIEW_DP),
            )
            AsyncImage(
                model = candidate.coverUrl,
                contentDescription = candidate.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(COVER_PREVIEW_DP).clip(RoundedCornerShape(10.dp))
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .08f), onClick = onCancel) {
                Text(stringResource(R.string.music_panel_rename_cancel), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp))
            }
            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.primary, onClick = { if (!saving) onConfirm() }) {
                Box(modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.music_panel_rename_confirm),
                        color = if (saving) Color.Transparent else MaterialTheme.colorScheme.onPrimary
                    )
                    if (saving) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }
        }
    }
}
