package com.yichao.evilgodxu.screens.home.component.queue

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.LocalPlaylistStore
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.ui.component.AppDialog
import com.yichao.evilgodxu.ui.component.DIALOG_LIST_HEIGHT_FRACTION
import com.yichao.evilgodxu.ui.component.PlaylistArt
import com.yichao.evilgodxu.ui.icons.AppIcons
import kotlinx.coroutines.launch

/**
 * 歌单归属弹窗：勾选加入、取消移出，即时落盘
 * 只列自定义歌单，派生歌单不可手动增删
 */
@Composable
internal fun TrackPlaylistDialog(
    track: MusicTrack?,
    libraryTracks: List<MusicTrack>,
    onDismiss: () -> Unit,
) {
    if (track == null) return
    val playlistStore = LocalPlaylistStore.current
    val scope = rememberCoroutineScope()
    val playlists = playlistStore.playlists
    // 按 id 查表取封面，避免逐行扫全库
    val libraryById = remember(libraryTracks) { libraryTracks.associateBy { it.id } }
    AppDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.playlist_membership_title),
        // 列表自带滚动，不能与外壳嵌套
        scrollable = false,
        contentHeightFraction = DIALOG_LIST_HEIGHT_FRACTION,
    ) {
        Text(
            text = listOf(track.title, track.artist)
                .filter { it.isNotBlank() }
                .joinToString(" - "),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        if (playlists.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.playlist_membership_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(playlists, key = { it.id }) { playlist ->
                    val isMember = track.id in playlist.trackIds
                    PlaylistMembershipRow(
                        name = playlist.name,
                        count = playlist.trackIds.size,
                        isMember = isMember,
                        coverTrack = playlist.trackIds.firstOrNull()?.let { libraryById[it] },
                        onClick = {
                            scope.launch {
                                if (isMember) {
                                    playlistStore.removeTracks(playlist.id, listOf(track.id))
                                } else {
                                    playlistStore.addTracks(playlist.id, listOf(track.id))
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

// 归属行：封面 + 名称 + 数量 + 状态图标
@Composable
private fun PlaylistMembershipRow(
    name: String,
    count: Int,
    isMember: Boolean,
    coverTrack: MusicTrack?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (coverTrack != null) {
                PlaylistArt(track = coverTrack, modifier = Modifier.fillMaxSize())
            } else {
                Icon(
                    imageVector = AppIcons.QueueMusic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = name,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 12.sp,
                lineHeight = 15.sp,
                fontWeight = if (isMember) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = pluralStringResource(R.plurals.music_panel_track_count, count, count),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                lineHeight = 12.sp,
            )
        }
        Icon(
            imageVector = if (isMember) AppIcons.Check else AppIcons.Add,
            contentDescription = null,
            tint = if (isMember) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp),
        )
    }
}