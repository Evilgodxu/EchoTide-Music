package com.yichao.evilgodxu.screens.home.component.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.data.music.metadata.MusicMetadataWriter
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.playlist.Playlist
import com.yichao.evilgodxu.data.playlist.PlaylistGroup
import com.yichao.evilgodxu.data.playlist.SmartPlaylistType
import com.yichao.evilgodxu.data.playlist.albumGroups
import com.yichao.evilgodxu.data.playlist.artistGroups
import com.yichao.evilgodxu.data.playlist.recentTracks
import com.yichao.evilgodxu.data.playlist.resolveTracks
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.data.music.playback.PlaylistSource
import com.yichao.evilgodxu.data.music.playback.playTrackAt
import com.yichao.evilgodxu.data.music.playback.togglePlayPause
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.LocalPlaylistStore
import com.yichao.evilgodxu.ui.component.BottomSearchBarOverlay
import com.yichao.evilgodxu.ui.component.DialogCard
import com.yichao.evilgodxu.ui.icons.AppIcons
import com.yichao.evilgodxu.ui.component.PlaylistArt
import com.yichao.evilgodxu.ui.component.RemoveTrackDialog
import com.yichao.evilgodxu.ui.component.SEARCH_BAR_REGION_DP
import com.yichao.evilgodxu.ui.component.smartTypeLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// 专辑/艺术家分组列表页
@Composable
internal fun PlaylistGroupsPage(
    type: SmartPlaylistType,
    playbackState: MusicPlaybackState,
    onOpenGroup: (PlaylistGroup) -> Unit,
) {
    val library = playbackState.libraryTracks
    val unknownAlbum = stringResource(R.string.playlist_unknown_album)
    val unknownArtist = stringResource(R.string.music_scanner_unknown_artist)
    // 分组是全库扫描 + 排序，缓存到曲库变化为止，避免每次重组重算
    val groups = remember(library, type, unknownAlbum, unknownArtist) {
        when (type) {
            SmartPlaylistType.ALBUM -> albumGroups(library, unknownAlbum)
            SmartPlaylistType.ARTIST -> artistGroups(library, unknownArtist)
            else -> emptyList()
        }
    }
    // 行内取封面按 id 查表，避免每行线性扫描全库
    val libraryById = remember(library) { library.associateBy { it.id } }
    val icon: ImageVector = if (type == SmartPlaylistType.ALBUM) AppIcons.Album else AppIcons.Person
    // 列表内搜索关键词：仅过滤展示，不改变曲库
    var searchQuery by remember { mutableStateOf("") }
    // 搜索框聚焦状态：输入期间常驻显示，不随列表滚动隐去
    var searchFocused by remember { mutableStateOf(false) }
    // 按条目名称过滤：关键字命中歌手名/专辑名即保留
    val visibleGroups = remember(groups, searchQuery) {
        if (searchQuery.isBlank()) {
            groups
        } else {
            groups.filter { it.name.contains(searchQuery, ignoreCase = true) }
        }
    }
    if (groups.isEmpty()) {
        EmptyHint(text = stringResource(R.string.playlist_empty))
    } else {
        val keyboardController = LocalSoftwareKeyboardController.current
        val listState = rememberLazyListState()
        // 列表滚动中隐藏搜索框，滚动停止自动恢复
        val isScrolling by remember { derivedStateOf { listState.isScrollInProgress } }
        val density = LocalDensity.current
        // 搜索框在列表底部占用的高度：最后一项底缘进入该区域即视为滚到底部
        val searchBarRegionPx = with(density) { SEARCH_BAR_REGION_DP.toPx() }
        // 滚到底部判定：最后一项已到达列表底部（底缘进入搜索框遮挡区）；
        // 列表不足一屏时最后一项不会触底，搜索框保持常驻
        val atBottom by remember {
            derivedStateOf {
                val layout = listState.layoutInfo
                val last = layout.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf false
                last.index == layout.totalItemsCount - 1 &&
                    last.offset + last.size >= layout.viewportEndOffset - searchBarRegionPx
            }
        }
        val searchHidden = (isScrolling || atBottom) && !searchFocused
        // imePadding 收紧页面底部：键盘弹出时压缩列表，搜索框保持在键盘上方
        Box(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
        ) {
            if (searchQuery.isNotBlank() && visibleGroups.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(
                            if (type == SmartPlaylistType.ALBUM) R.string.playlist_search_no_album_results
                            else R.string.playlist_search_no_artist_results
                        ),
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 12.sp,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(visibleGroups, key = { it.key }) { group ->
                        // 专辑/艺术家歌单封面统一采用该歌单内第一首歌曲的封面
                        val coverTrack = group.trackIds.firstOrNull()?.let { libraryById[it] }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    keyboardController?.hide()
                                    onOpenGroup(group)
                                }
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
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Text(
                                    text = group.name,
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    lineHeight = 15.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = stringResource(R.string.music_panel_track_count, group.trackIds.size),
                                    color = Color.White.copy(alpha = 0.6f),
                                    fontSize = 10.sp,
                                    lineHeight = 12.sp,
                                )
                            }
                            Icon(
                                imageVector = AppIcons.KeyboardArrowRight,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
            // 底部搜索框：滚到底部或滚动中隐藏，避免遮挡末尾条目；输入中常驻。
            // 本面板为深色沉浸背景，前景统一走白色系，与上方条目文字一致
            BottomSearchBarOverlay(
                hidden = searchHidden,
                placeholder = stringResource(
                    if (type == SmartPlaylistType.ALBUM) R.string.playlist_search_album_placeholder
                    else R.string.playlist_search_artist_placeholder
                ),
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                onFocusChanged = { searchFocused = it },
                borderColor = Color.White.copy(alpha = 0.45f),
                textColor = Color.White,
                hintColor = Color.White.copy(alpha = 0.6f),
            )
        }
    }
}

// 自定义歌单曲目页：支持添加歌曲与移除
@Composable
internal fun PlaylistTracksPage(
    playlist: Playlist,
    playbackState: MusicPlaybackState,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playlistStore = LocalPlaylistStore.current
    // 实时读取最新歌单，添加/移除歌曲后立即刷新列表
    val currentPlaylist = playlistStore.playlists.find { it.id == playlist.id } ?: playlist
    val tracks = remember(playbackState.libraryTracks, currentPlaylist.trackIds) {
        resolveTracks(playbackState.libraryTracks, currentPlaylist.trackIds)
    }
    var showPicker by remember { mutableStateOf(false) }
    var removeTrack by remember { mutableStateOf<MusicTrack?>(null) }
    TracksContent(
        tracks = tracks,
        source = PlaylistSource("custom:${playlist.id}", currentPlaylist.name),
        playbackState = playbackState,
        scope = scope,
        trailingAction = {
            TextButton(onClick = { showPicker = true }) {
                Text(
                    text = stringResource(R.string.playlist_add_songs),
                    color = Color.White,
                    fontWeight = FontWeight.Medium,
                )
            }
        },
        onTrackLongClick = { removeTrack = it },
        onReorder = { ordered ->
            playlistStore.setTrackOrder(context, playlist.id, ordered.map { it.id })
        },
    )
    AddSongsPicker(
        visible = showPicker,
        playlistName = currentPlaylist.name,
        candidateTracks = playbackState.libraryTracks,
        existingIds = currentPlaylist.trackIds.toSet(),
        onConfirm = { ids ->
            playlistStore.addTracks(context, playlist.id, ids)
            showPicker = false
        },
        onDismiss = { showPicker = false },
    )
    RemoveTrackDialog(
        track = removeTrack,
        onConfirm = { track ->
            playlistStore.removeTracks(context, playlist.id, listOf(track.id))
            removeTrack = null
        },
        onDismiss = { removeTrack = null },
    )
}

// 系统歌单曲目页（常听/收藏）
@Composable
internal fun PlaylistSmartTracksPage(
    type: SmartPlaylistType,
    playbackState: MusicPlaybackState,
) {
    val scope = rememberCoroutineScope()
    val library = playbackState.libraryTracks
    val tracks = remember(library, playbackState.recentPlayedIds, playbackState.likedIds) {
        when (type) {
            SmartPlaylistType.RECENT -> recentTracks(library, playbackState.recentPlayedIds)
            SmartPlaylistType.FAVORITE -> library.filter { it.id in playbackState.likedIds }
            else -> emptyList()
        }
    }
    // 常听歌单长按歌曲：从常听中移除该曲目
    var removeTrack by remember { mutableStateOf<MusicTrack?>(null) }
    TracksContent(
        tracks = tracks,
        source = PlaylistSource("smart:${type.name}", smartTypeLabel(type)),
        playbackState = playbackState,
        scope = scope,
        trailingAction = {},
        onTrackLongClick = { track ->
            if (type == SmartPlaylistType.RECENT) removeTrack = track
        },
    )
    RemoveTrackDialog(
        track = removeTrack,
        onConfirm = { track ->
            playbackState.removeFromRecentPlayed(track.id)
            removeTrack = null
        },
        onDismiss = { removeTrack = null },
    )
}

// 智能分组曲目页（专辑/艺术家下的曲目列表）
@Composable
internal fun PlaylistGroupTracksPage(
    type: SmartPlaylistType,
    group: PlaylistGroup,
    playbackState: MusicPlaybackState,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val tracks = remember(playbackState.libraryTracks, group.key) {
        if (type == SmartPlaylistType.ALBUM) {
            val albumId = group.key.removePrefix("album:").toLongOrNull()
            playbackState.libraryTracks.filter { it.albumId == albumId }
        } else {
            // 与分组列表一致：按分组保存的 trackIds 解析，避免多歌手曲目（如 "A / B"）
            // 因 artist 整串不等于歌手名而被过滤，导致详情曲目少于列表展示数
            resolveTracks(playbackState.libraryTracks, group.trackIds)
        }
    }
    // 专辑视图长按歌曲：编辑该歌曲的专辑元数据
    var editAlbumTrack by remember { mutableStateOf<MusicTrack?>(null) }
    TracksContent(
        tracks = tracks,
        source = PlaylistSource(group.key, group.name),
        playbackState = playbackState,
        scope = scope,
        trailingAction = {},
        onTrackLongClick = { track ->
            if (type == SmartPlaylistType.ALBUM) editAlbumTrack = track
        },
    )
    EditAlbumDialog(
        track = editAlbumTrack,
        onConfirm = { track, albumName ->
            playbackState.updateTrack(track.copy(albumName = albumName))
            editAlbumTrack = null
            // 将专辑名写回音频文件标签
            scope.launch {
                MusicMetadataWriter.writeAlbum(context, track, albumName)
            }
        },
        onDismiss = { editAlbumTrack = null },
    )
}

// 曲目页共享主体：播放全部 + 操作栏 + 曲目列表（支持长按手柄拖拽排序）
@Composable
private fun TracksContent(
    tracks: List<MusicTrack>,
    source: PlaylistSource?,
    playbackState: MusicPlaybackState,
    scope: CoroutineScope,
    trailingAction: @Composable () -> Unit,
    onTrackLongClick: (MusicTrack) -> Unit,
    onReorder: ((List<MusicTrack>) -> Unit)? = null,
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    // 可排序的本地列表：拖拽实时重排，随数据源变化重置
    var orderedTracks by remember(tracks) { mutableStateOf(tracks) }
    // 自实现 reorderable 状态：拖拽项跟随手指，其余项使用 animateItem 平滑让位
    val reorderableState = rememberReorderableLazyListState(listState) { from, to ->
        val list = orderedTracks.toMutableList()
        val moved = list.removeAt(from)
        list.add(to, moved)
        orderedTracks = list
        // 与当前播放来源一致时实时同步播放队列顺序
        if (playbackState.playlistSource?.key == source?.key) {
            playbackState.reorderPlaylist(list)
        }
        onReorder?.invoke(list)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = { playQueue(context, playbackState, scope, orderedTracks, 0, source) },
                enabled = orderedTracks.isNotEmpty(),
            ) {
                Icon(
                    imageVector = AppIcons.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.playlist_play_all),
                    color = Color.White,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            trailingAction()
            Text(
                text = stringResource(R.string.music_panel_track_count, orderedTracks.size),
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 10.sp,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        if (orderedTracks.isEmpty()) {
            EmptyHint(text = stringResource(R.string.playlist_empty))
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(orderedTracks, key = { _, track -> track.audioUri }) { index, track ->
                    val isActive = track.id == playbackState.currentTrack?.id
                    ReorderableItem(reorderableState, key = track.audioUri) { isDragging ->
                        PlaylistTrackRow(
                            track = track,
                            isActive = isActive,
                            isPlaying = isActive && playbackState.isPlaying,
                            isLiked = track.id in playbackState.likedIds,
                            isDragging = isDragging,
                            onClick = {
                                if (isActive) {
                                    togglePlayPause(playbackState)
                                } else {
                                    playQueue(context, playbackState, scope, orderedTracks, index, source)
                                }
                            },
                            onLongClick = { onTrackLongClick(track) },
                            onFavoriteClick = { playbackState.toggleFavorite(track.id) },
                            dragHandleModifier = Modifier.longPressDraggableHandle(reorderableState, track.audioUri),
                        )
                    }
                }
            }
        }
    }
}

// 空态提示
@Composable
private fun EmptyHint(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 12.sp,
        )
    }
}

// 将歌单曲目设为当前播放队列并切入指定曲目，同时记录来源歌单
private fun playQueue(
    context: android.content.Context,
    state: MusicPlaybackState,
    scope: CoroutineScope,
    tracks: List<MusicTrack>,
    startIndex: Int,
    source: PlaylistSource?,
) {
    if (tracks.isEmpty()) return
    // 首次从默认库切到歌单时备份默认列表，供快捷切回
    if (state.playlistSource == null && state.defaultPlaylistBackup == null) {
        state.defaultPlaylistBackup = state.playlist
    }
    state.playlist = tracks
    state.playlistSource = source
    val index = startIndex.coerceIn(0, tracks.size - 1)
    state.currentIndex = index
    scope.launch { playTrackAt(context, state, index) }
    state.persistPlaylist()
}

// 专辑视图长按歌曲：编辑该歌曲的专辑元数据
@Composable
internal fun EditAlbumDialog(
    track: MusicTrack?,
    onConfirm: (MusicTrack, String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (track == null) return
    DialogCard(onDismiss = onDismiss) {
        var value by remember(track.id) { mutableStateOf(track.albumName) }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.playlist_edit_album_title),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.widthIn(max = 200.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                    onClick = onDismiss,
                ) {
                    Text(
                        text = stringResource(R.string.music_panel_rename_cancel),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                }
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primary,
                    onClick = {
                        val trimmed = value.trim()
                        if (trimmed.isNotEmpty()) onConfirm(track, trimmed)
                    },
                ) {
                    Text(
                        text = stringResource(R.string.music_panel_rename_confirm),
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                }
            }
        }
    }
}
