package com.yichao.evilgodxu.screens.home.component.playlist.expanded

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.data.playlist.Playlist
import com.yichao.evilgodxu.data.playlist.PlaylistStore
import com.yichao.evilgodxu.data.playlist.SmartPlaylistType
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.screens.home.component.playlist.CreatePlaylistRow
import com.yichao.evilgodxu.screens.home.component.playlist.PlaylistListRow
import com.yichao.evilgodxu.screens.home.component.playlist.SectionLabel
import com.yichao.evilgodxu.screens.home.component.playlist.SmartPlaylistCounts
import com.yichao.evilgodxu.screens.home.component.playlist.rememberSmartPlaylistCounts
import com.yichao.evilgodxu.screens.home.component.playlist.smartTypeIcon
import com.yichao.evilgodxu.ui.component.CoverPrefetch
import com.yichao.evilgodxu.ui.component.smartTypeLabel

// 宽屏歌单面板左栏：系统歌单四项（图标 + 名称 + 计数，选中项高亮）+ 我的歌单列表 + 新建入口。
// 横向宽度在此充当常驻导航，点选即换右栏内容，不再逐级下钻；我的歌单超出高度时本栏内滚动，
// 新建入口固定在栏底，歌单再多也不需要滚到底才能新建
@Composable
internal fun PlaylistRail(
    playbackState: MusicPlaybackState,
    playlistStore: PlaylistStore,
    selected: PlaylistDestination,
    onSelect: (PlaylistDestination) -> Unit,
    onCreatePlaylist: () -> Unit,
    onRename: (Playlist) -> Unit,
    onDelete: (Playlist) -> Unit,
    menuBackgroundColor: Color,
    modifier: Modifier = Modifier,
) {
    val counts = rememberSmartPlaylistCounts(playbackState)
    val libraryById = remember(playbackState.libraryTracks) {
        playbackState.libraryTracks.associateBy { it.id }
    }
    val listState = rememberLazyListState()
    // 歌单行封面取歌单首曲：可视区邻域提前取图，滚动进入视口时不再先闪占位符。
    // 歌单行由 [RAIL_PLAYLIST_ITEM_OFFSET] 之后开始，故列表下标需先扣除头部项
    CoverPrefetch(listState) { index ->
        playlistStore.playlists.getOrNull(index - RAIL_PLAYLIST_ITEM_OFFSET)
            ?.trackIds
            ?.firstNotNullOfOrNull { libraryById[it] }
    }
    Column(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item {
                SectionLabel(text = stringResource(R.string.playlist_section_smart))
                Spacer(modifier = Modifier.height(8.dp))
            }
            items(SmartPlaylistType.entries, key = { it.name }) { type ->
                SmartRailItem(
                    icon = smartTypeIcon(type),
                    label = smartTypeLabel(type),
                    countText = smartCountText(type, counts),
                    selected = selected == PlaylistDestination.Smart(type),
                    onClick = { onSelect(PlaylistDestination.Smart(type)) },
                )
            }
            item {
                Spacer(modifier = Modifier.height(14.dp))
                SectionLabel(text = stringResource(R.string.playlist_section_my))
                Spacer(modifier = Modifier.height(6.dp))
            }
            items(playlistStore.playlists, key = { it.id }) { playlist ->
                // 逐行缓存解析结果：数量与库大小无关地重复查表会形成 O(歌单数 × 库大小)
                val playlistTracks = remember(playlist.trackIds, libraryById) {
                    playlist.trackIds.mapNotNull { libraryById[it] }
                }
                PlaylistListRow(
                    playlist = playlist,
                    count = playlistTracks.size,
                    coverTrack = playlistTracks.firstOrNull(),
                    menuBackgroundColor = menuBackgroundColor,
                    onClick = { onSelect(PlaylistDestination.Custom(playlist)) },
                    onRename = { onRename(playlist) },
                    onDelete = { onDelete(playlist) },
                    selected = selected == PlaylistDestination.Custom(playlist),
                )
            }
            item { Spacer(modifier = Modifier.height(10.dp)) }
        }
        CreatePlaylistRow(
            onClick = onCreatePlaylist,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

// 系统歌单导航项：图标 + 名称 + 计数，选中项以浅色底与左侧主色条标示
@Composable
private fun SmartRailItem(
    icon: ImageVector,
    label: String,
    countText: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .then(if (selected) Modifier.background(Color.White.copy(alpha = 0.13f)) else Modifier)
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = label,
                color = Color.White,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = countText,
                color = if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.6f),
                fontSize = 11.sp,
            )
        }
        if (selected) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 3.dp)
                    .size(width = 3.dp, height = 20.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

// 系统歌单计数文案：常听/收藏按曲目数，专辑/歌手按去重分组数
@Composable
private fun smartCountText(type: SmartPlaylistType, counts: SmartPlaylistCounts): String = when (type) {
    SmartPlaylistType.RECENT -> pluralStringResource(R.plurals.music_panel_track_count, counts.recent, counts.recent)
    SmartPlaylistType.FAVORITE -> pluralStringResource(R.plurals.music_panel_track_count, counts.favorite, counts.favorite)
    SmartPlaylistType.ALBUM -> pluralStringResource(R.plurals.playlist_album_count, counts.album, counts.album)
    SmartPlaylistType.ARTIST -> pluralStringResource(R.plurals.playlist_artist_count, counts.artist, counts.artist)
}

// 左栏列表里歌单行之前的头部项数：系统歌单分节标题、四项系统歌单、我的歌单分节标题。
// 预取按列表下标解析歌单时须按此回退，新增头部项时同步调整
private const val RAIL_PLAYLIST_ITEM_OFFSET = 6