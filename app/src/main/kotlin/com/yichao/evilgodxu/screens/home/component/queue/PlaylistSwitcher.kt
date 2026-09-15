package com.yichao.evilgodxu.screens.home.component.queue

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.yichao.evilgodxu.LocalPlaylistStore
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.playlist.PlaylistGroup
import com.yichao.evilgodxu.data.playlist.PlaylistStore
import com.yichao.evilgodxu.data.playlist.SmartPlaylistType
import com.yichao.evilgodxu.data.playlist.albumGroups
import com.yichao.evilgodxu.data.playlist.artistGroups
import com.yichao.evilgodxu.data.playlist.smartTrackCount
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.data.music.playback.PlaylistSource
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.ui.icons.AppIcons
import com.yichao.evilgodxu.ui.component.PlaylistArt
import com.yichao.evilgodxu.ui.component.smartTypeLabel

// 播放列表副标题快捷切换歌单弹层：默认 + 系统歌单 + 自定义歌单，专辑/艺术家支持分组二级导航
// 选择结果仅回调所选来源给调用方切换面板展示内容，不直接改动播放状态
@Composable
internal fun PlaylistSwitcher(
    visible: Boolean,
    playbackState: MusicPlaybackState,
    currentKey: String?,
    onSwitch: (PlaylistSource?) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    val playlistStore = LocalPlaylistStore.current
    var showGroups by remember { mutableStateOf<SmartPlaylistType?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.36f)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showGroups != null) {
                    IconButton(onClick = { showGroups = null }) {
                        Icon(
                            imageVector = AppIcons.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                Text(
                    text = if (showGroups == null) stringResource(R.string.playlist_switch_title)
                    else smartTypeLabel(showGroups!!),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = AppIcons.Close,
                        contentDescription = stringResource(R.string.back),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            val type = showGroups
            if (type == null) {
                PlaylistSwitchList(
                    playlistStore = playlistStore,
                    playbackState = playbackState,
                    currentKey = currentKey,
                    onSwitch = { source ->
                        onSwitch(source)
                        onDismiss()
                    },
                    onOpenGroups = { showGroups = it },
                )
            } else {
                PlaylistSwitchGroups(
                    type = type,
                    playbackState = playbackState,
                    currentKey = currentKey,
                    onSwitch = { source ->
                        onSwitch(source)
                        onDismiss()
                    },
                )
            }
        }
    }
}

// 一级列表：默认播放列表 + 常听/收藏 + 专辑/艺术家入口 + 我的歌单
@Composable
private fun PlaylistSwitchList(
    playlistStore: PlaylistStore,
    playbackState: MusicPlaybackState,
    currentKey: String?,
    onSwitch: (PlaylistSource?) -> Unit,
    onOpenGroups: (SmartPlaylistType) -> Unit,
) {
    val library = playbackState.libraryTracks
    // 行内取封面按 id 查表，避免每行线性扫描全库
    val libraryById = remember(library) { library.associateBy { it.id } }
    val recentLabel = stringResource(R.string.playlist_smart_recent)
    val favoriteLabel = stringResource(R.string.playlist_smart_favorite)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item {
            SwitchRow(
                icon = AppIcons.QueueMusic,
                title = stringResource(R.string.playlist_switch_default),
                subtitle = stringResource(R.string.music_panel_track_count, library.size),
                isCurrent = currentKey == null,
                onClick = { onSwitch(null) },
            )
        }
        item {
            SwitchRow(
                icon = AppIcons.History,
                title = recentLabel,
                subtitle = stringResource(R.string.music_panel_track_count, smartTrackCount(library, playbackState.recentPlayedIds)),
                isCurrent = currentKey == "smart:RECENT",
                onClick = {
                    onSwitch(PlaylistSource("smart:RECENT", recentLabel))
                },
            )
        }
        item {
            SwitchRow(
                icon = AppIcons.Favorite,
                title = favoriteLabel,
                subtitle = stringResource(R.string.music_panel_track_count, smartTrackCount(library, playbackState.likedIds)),
                isCurrent = currentKey == "smart:FAVORITE",
                onClick = {
                    onSwitch(PlaylistSource("smart:FAVORITE", favoriteLabel))
                },
            )
        }
        item {
            SwitchRow(
                icon = AppIcons.Album,
                title = stringResource(R.string.playlist_smart_album),
                subtitle = "",
                isCurrent = false,
                showChevron = true,
                onClick = { onOpenGroups(SmartPlaylistType.ALBUM) },
            )
        }
        item {
            SwitchRow(
                icon = AppIcons.Person,
                title = stringResource(R.string.playlist_smart_artist),
                subtitle = "",
                isCurrent = false,
                showChevron = true,
                onClick = { onOpenGroups(SmartPlaylistType.ARTIST) },
            )
        }
        item {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.playlist_section_my),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 12.dp),
            )
            Spacer(modifier = Modifier.height(6.dp))
        }
        items(playlistStore.playlists, key = { it.id }) { playlist ->
            SwitchRow(
                icon = AppIcons.QueueMusic,
                title = playlist.name,
                subtitle = stringResource(R.string.music_panel_track_count, playlist.trackIds.size),
                isCurrent = currentKey == "custom:${playlist.id}",
                coverTrack = playlist.trackIds.firstOrNull()?.let { libraryById[it] },
                onClick = {
                    onSwitch(PlaylistSource("custom:${playlist.id}", playlist.name))
                },
            )
        }
        item { Spacer(modifier = Modifier.height(12.dp)) }
    }
}

// 二级列表：专辑/艺术家分组
@Composable
private fun PlaylistSwitchGroups(
    type: SmartPlaylistType,
    playbackState: MusicPlaybackState,
    currentKey: String?,
    onSwitch: (PlaylistSource?) -> Unit,
) {
    val library = playbackState.libraryTracks
    val unknownAlbum = stringResource(R.string.playlist_unknown_album)
    val unknownArtist = stringResource(R.string.music_scanner_unknown_artist)
    // 分组是全库扫描 + 排序，缓存到曲库变化为止，避免每次重组重算
    val groups: List<PlaylistGroup> = remember(library, type, unknownAlbum, unknownArtist) {
        if (type == SmartPlaylistType.ALBUM) {
            albumGroups(library, unknownAlbum)
        } else {
            artistGroups(library, unknownArtist)
        }
    }
    val libraryById = remember(library) { library.associateBy { it.id } }
    val icon: ImageVector = if (type == SmartPlaylistType.ALBUM) AppIcons.Album else AppIcons.Person
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(groups, key = { it.key }) { group ->
            // 按分组自带的 id 列表查表还原曲目：逐行 filter 全库会形成 O(分组数 × 库大小)，
            // 且原按 group.name 反查会漏掉「未知艺术家」组（其 name 是兜底文案，不等于实际 artist）
            val tracks = remember(group.key, libraryById) {
                group.trackIds.mapNotNull { libraryById[it] }
            }
            SwitchRow(
                icon = icon,
                title = group.name,
                subtitle = stringResource(R.string.music_panel_track_count, group.trackIds.size),
                isCurrent = currentKey == group.key,
                coverTrack = tracks.firstOrNull(),
                onClick = { onSwitch(PlaylistSource(group.key, group.name)) },
            )
        }
    }
}

// 切换项行：图标/封面 + 名称 + 数量 + 当前标识/下级箭头，几何与排版对齐歌单列表行
@Composable
private fun SwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isCurrent: Boolean,
    onClick: () -> Unit,
    showChevron: Boolean = false,
    coverTrack: MusicTrack? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                else Color.Transparent,
            )
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
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 12.sp,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                )
            }
        }
        when {
            isCurrent -> Icon(
                imageVector = AppIcons.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            showChevron -> Icon(
                imageVector = AppIcons.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
