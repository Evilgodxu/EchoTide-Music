package com.yichao.evilgodxu.screens.home.component.queue

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import com.yichao.evilgodxu.ui.icons.AppIcons

/**
 * 曲目歌单归属弹窗：列出全部自定义歌单，勾选即加入、取消勾选即移出。
 *
 * 只列自定义歌单 —— 常听/收藏/专辑/艺术家由播放、收藏或元数据派生，不能手动增删曲目。
 * 归属变更即时落盘，弹窗不随单次勾选关闭，便于一次调整多个歌单。
 */
@Composable
internal fun TrackPlaylistDialog(
    track: MusicTrack?,
    onDismiss: () -> Unit,
) {
    if (track == null) return
    val context = LocalContext.current
    val playlistStore = LocalPlaylistStore.current
    val playlists = playlistStore.playlists
    AppDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.playlist_membership_title),
        // 列表自带 LazyColumn 滚动，不能与外壳滚动嵌套
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
                        onClick = {
                            if (isMember) {
                                playlistStore.removeTracks(context, playlist.id, listOf(track.id))
                            } else {
                                playlistStore.addTracks(context, playlist.id, listOf(track.id))
                            }
                        },
                    )
                }
            }
        }
    }
}

// 歌单归属行：名称 + 曲目数 + 归属状态图标；勾选态用主色，未勾选用浅色加号提示可加入
@Composable
private fun PlaylistMembershipRow(
    name: String,
    count: Int,
    isMember: Boolean,
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
            Icon(
                imageVector = AppIcons.QueueMusic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 12.sp,
                fontWeight = if (isMember) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = pluralStringResource(R.plurals.music_panel_track_count, count, count),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
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