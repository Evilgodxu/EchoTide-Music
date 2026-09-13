package com.yichao.evilgodxu.ui.component

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.yichao.evilgodxu.data.music.api.sourceNameRes
import com.yichao.evilgodxu.data.music.model.MusicSearchSource
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.model.NeteaseSongSearchResult
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.R

@Composable
internal fun LyricsRefreshOverlay(
    visible: Boolean,
    track: MusicTrack?,
    playbackState: MusicPlaybackState,
    context: Context,
    selectedId: Long?,
    onCandidateSelected: (NeteaseSongSearchResult) -> Unit,
    onSourceSelected: (MusicSearchSource) -> Unit,
    onRefresh: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    RefreshCandidateOverlay(
        visible = visible && track != null,
        searching = playbackState.isLyricsSearching || playbackState.isLyricsRefreshing,
        saving = false,
        candidates = playbackState.lyricsCandidates,
        selectedId = selectedId,
        source = playbackState.lyricsRefreshSource,
        titleText = stringResource(playbackState.lyricsRefreshSource.sourceNameRes()),
        refreshLabel = stringResource(R.string.music_panel_refresh_lyrics),
        noCandidatesText = stringResource(R.string.music_panel_lyrics_no_candidates),
        onSourceSelected = onSourceSelected,
        onRefresh = onRefresh,
        onCandidateSelected = onCandidateSelected,
        onConfirm = onConfirm,
        onCancel = onCancel,
        candidateItem = { candidate, selected ->
            LyricsCandidateItem(candidate = candidate, selected = selected, context = context, onSelected = onCandidateSelected)
        },
        topBanner = {
            playbackState.lyricsRefreshError?.let { error ->
                MusicErrorBanner(
                    message = error,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    onDismiss = { playbackState.setLyricsRefreshError(null) }
                )
            }
        },
    )
}

@Composable
internal fun LyricsRefreshDialog(
    visible: Boolean,
    track: MusicTrack?,
    playbackState: MusicPlaybackState,
    context: Context,
    selectedId: Long?,
    onCandidateSelected: (NeteaseSongSearchResult) -> Unit,
    onSourceSelected: (MusicSearchSource) -> Unit,
    onRefresh: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    RefreshCandidateDialog(
        visible = visible && track != null,
        searching = playbackState.isLyricsSearching || playbackState.isLyricsRefreshing,
        saving = false,
        candidates = playbackState.lyricsCandidates,
        selectedId = selectedId,
        source = playbackState.lyricsRefreshSource,
        titleText = stringResource(playbackState.lyricsRefreshSource.sourceNameRes()),
        refreshLabel = stringResource(R.string.music_panel_refresh_lyrics),
        noCandidatesText = stringResource(R.string.music_panel_lyrics_no_candidates),
        onSourceSelected = onSourceSelected,
        onRefresh = onRefresh,
        onCandidateSelected = onCandidateSelected,
        onConfirm = onConfirm,
        onCancel = onCancel,
        candidateItem = { candidate, selected ->
            LyricsCandidateItem(candidate = candidate, selected = selected, context = context, onSelected = onCandidateSelected)
        },
        footer = {
            playbackState.lyricsRefreshError?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
    )
}

// 歌词候选条目：封面(或来源名占位) + 标题 + 歌手（内容形态与封面包围条目不同，故注入）
@Composable
private fun LyricsCandidateItem(
    candidate: NeteaseSongSearchResult,
    selected: Boolean,
    context: Context,
    onSelected: (NeteaseSongSearchResult) -> Unit,
) {
    Column(
        Modifier
            .width(112.dp)
            .clickable { onSelected(candidate) },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        ) {
            val coverUrl = candidate.coverUrl?.takeIf { it.isNotBlank() }
            Box(Modifier.size(96.dp).clip(RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                if (coverUrl != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(context).data(coverUrl).diskCachePolicy(CachePolicy.DISABLED).build(),
                        contentDescription = candidate.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        stringResource(candidate.source.sourceNameRes()),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
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
