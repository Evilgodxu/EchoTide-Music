package com.yichao.evilgodxu.screens.home.component.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.data.music.metadata.MetadataEnricher
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.playlist.Playlist
import com.yichao.evilgodxu.data.playlist.PlaylistGroup
import com.yichao.evilgodxu.data.playlist.SmartPlaylistType
import com.yichao.evilgodxu.data.playlist.albumGroups
import com.yichao.evilgodxu.data.playlist.artistGroups
import com.yichao.evilgodxu.data.playlist.playlistCoverTrack
import com.yichao.evilgodxu.data.playlist.resolveTracks
import com.yichao.evilgodxu.data.playlist.resolveSourceTracks
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.data.music.playback.PlaylistSource
import com.yichao.evilgodxu.data.music.playback.switchToPlaylistQueue
import com.yichao.evilgodxu.data.music.playback.togglePlayPause
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.LocalMetadataEnricher
import com.yichao.evilgodxu.LocalAlbumOrderStore
import com.yichao.evilgodxu.LocalArtistOrderStore
import com.yichao.evilgodxu.LocalFavoriteOrderStore
import com.yichao.evilgodxu.LocalPlaylistStore
import com.yichao.evilgodxu.ui.component.BottomSearchBarOverlay
import com.yichao.evilgodxu.ui.component.CoverPrefetch
import com.yichao.evilgodxu.ui.component.fadeUnderBottomSearchBar
import com.yichao.evilgodxu.ui.icons.AppIcons
import com.yichao.evilgodxu.ui.component.PlaylistArt
import com.yichao.evilgodxu.ui.component.RemoveTrackDialog
import com.yichao.evilgodxu.ui.component.SEARCH_BAR_BORDER_ALPHA
import com.yichao.evilgodxu.ui.component.SEARCH_BAR_FILL_ALPHA
import com.yichao.evilgodxu.ui.component.SEARCH_BAR_LIST_FADE_FEATHER_DP
import com.yichao.evilgodxu.ui.component.SEARCH_BAR_REGION_DP
import com.yichao.evilgodxu.ui.component.smartTypeLabel
import kotlinx.coroutines.launch

@Composable
internal fun PlaylistGroupsPage(
    type: SmartPlaylistType,
    playbackState: MusicPlaybackState,
    onOpenGroup: (PlaylistGroup) -> Unit,
) {
    val library = playbackState.libraryTracks
    val unknownAlbum = stringResource(R.string.playlist_unknown_album)
    val unknownArtist = stringResource(R.string.music_scanner_unknown_artist)
    // 分组扫描排序较重，缓存到曲库变化
    val groups = remember(library, type, unknownAlbum, unknownArtist) {
        when (type) {
            SmartPlaylistType.ALBUM -> albumGroups(library, unknownAlbum)
            SmartPlaylistType.ARTIST -> artistGroups(library, unknownArtist)
            else -> emptyList()
        }
    }
    // 按 id 查表取封面，避免逐行扫全库
    val libraryById = remember(library) { library.associateBy { it.id } }
    val albumOrders = LocalAlbumOrderStore.current.orders
    val artistOrders = LocalArtistOrderStore.current.orders
    // 封面与详情共用存档曲序，调整次序后同步
    val savedOrders = when (type) {
        SmartPlaylistType.ALBUM -> albumOrders
        SmartPlaylistType.ARTIST -> artistOrders
        else -> emptyMap()
    }
    val icon: ImageVector = if (type == SmartPlaylistType.ALBUM) AppIcons.Album else AppIcons.Person
    // 列表内搜索关键词：仅过滤展示，不改变曲库
    var searchQuery by remember { mutableStateOf("") }
    // 按名称过滤，命中歌手/专辑即保留
    val visibleGroups = remember(groups, searchQuery) {
        if (searchQuery.isBlank()) {
            groups
        } else {
            groups.filter { it.name.contains(searchQuery, ignoreCase = true) }
        }
    }
    // 展示与预取共用同一份封面结果
    val coverTracks = remember(groups, libraryById, savedOrders, type) {
        groups.associate { group ->
            val orderKey = if (type == SmartPlaylistType.ALBUM) group.key else group.name
            group.key to playlistCoverTrack(
                tracksById = libraryById,
                trackIds = group.trackIds,
                savedTrackIds = savedOrders[orderKey].orEmpty(),
            )
        }
    }
    if (groups.isEmpty()) {
        EmptyHint(text = stringResource(R.string.playlist_empty))
    } else {
        val keyboardController = LocalSoftwareKeyboardController.current
        val listState = rememberLazyListState()
        // 封面预取，进入视口不闪占位符
        CoverPrefetch(listState) { index ->
            visibleGroups.getOrNull(index)?.let { coverTracks[it.key] }
        }
        // 底部 inset 取 ime ∪ 导航栏，键盘只压缩列表
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
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
                        .padding(horizontal = 12.dp)
                        .fadeUnderBottomSearchBar(SEARCH_BAR_LIST_FADE_FEATHER_DP),
                    // 底部留白只取搜索框占高
                    contentPadding = PaddingValues(bottom = SEARCH_BAR_REGION_DP),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(visibleGroups, key = { it.key }) { group ->
                        PlaylistGroupRow(
                            group = group,
                            // 与自定义歌单同口径，取当前首曲封面
                            coverTrack = coverTracks[group.key],
                            fallbackIcon = icon,
                            onClick = {
                                keyboardController?.hide()
                                onOpenGroup(group)
                            },
                        )
                    }
                }
            }
            // 搜索框常驻；深色背景下统一白色系
            BottomSearchBarOverlay(
                placeholder = stringResource(
                    if (type == SmartPlaylistType.ALBUM) R.string.playlist_search_album_placeholder
                    else R.string.playlist_search_artist_placeholder
                ),
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                onFocusChanged = {},
                fillColor = Color.White.copy(alpha = SEARCH_BAR_FILL_ALPHA),
                borderColor = Color.White.copy(alpha = SEARCH_BAR_BORDER_ALPHA),
                textColor = Color.White,
                hintColor = Color.White.copy(alpha = 0.6f),
            )
        }
    }
}

// 分组行：封面 + 名称 + 数量 + 箭头；无曲目回落图标
@Composable
internal fun PlaylistGroupRow(
    group: PlaylistGroup,
    coverTrack: MusicTrack?,
    fallbackIcon: ImageVector,
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
                    imageVector = fallbackIcon,
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
                text = pluralStringResource(R.plurals.music_panel_track_count, group.trackIds.size, group.trackIds.size),
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

@Composable
internal fun PlaylistTracksPage(
    playlist: Playlist,
    playbackState: MusicPlaybackState,
) {
    val playlistStore = LocalPlaylistStore.current
    val scope = rememberCoroutineScope()
    // 读最新歌单，增删后立即刷新
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
        trailingAction = {
            // 用紧凑点击区，避免 IconButton 撑高操作栏
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { showPicker = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = AppIcons.PostAdd,
                    contentDescription = stringResource(R.string.playlist_add_songs),
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        },
        onTrackLongClick = { removeTrack = it },
        onReorder = { ordered ->
            scope.launch { playlistStore.setTrackOrder(playlist.id, ordered.map { it.id }) }
        },
    )
    AddSongsPicker(
        visible = showPicker,
        playlistName = currentPlaylist.name,
        candidateTracks = playbackState.libraryTracks,
        existingIds = currentPlaylist.trackIds.toSet(),
        onConfirm = { ids ->
            scope.launch { playlistStore.addTracks(playlist.id, ids) }
            showPicker = false
        },
        onDismiss = { showPicker = false },
    )
    RemoveTrackDialog(
        track = removeTrack,
        onConfirm = { track ->
            scope.launch { playlistStore.removeTracks(playlist.id, listOf(track.id)) }
            removeTrack = null
        },
        onDismiss = { removeTrack = null },
    )
}

@Composable
internal fun PlaylistSmartTracksPage(
    type: SmartPlaylistType,
    playbackState: MusicPlaybackState,
) {
    val library = playbackState.libraryTracks
    val favoriteOrderStore = LocalFavoriteOrderStore.current
    val favoriteOrder = favoriteOrderStore.order
    val scope = rememberCoroutineScope()
    val source = PlaylistSource("smart:${type.name}", smartTypeLabel(type))
    // type 参与缓存键，避免切类目串用旧结果
    val tracks = remember(library, playbackState.recentPlayedIds, playbackState.likedIds, favoriteOrder, type) {
        resolveSourceTracks(
            all = library,
            playlists = emptyList(),
            likedIds = playbackState.likedIds,
            recentPlayedIds = playbackState.recentPlayedIds,
            source = source,
            albumOrders = emptyMap(),
            artistOrders = emptyMap(),
            favoriteOrder = favoriteOrder,
        )
    }
    // 收藏可拖拽并落盘；常听跟随时间线，不可拖拽
    val reorder: ((List<MusicTrack>) -> Unit)? = if (type == SmartPlaylistType.FAVORITE) {
        { ordered -> scope.launch { favoriteOrderStore.setOrder(ordered.map { it.id }) } }
    } else {
        null
    }
    // 常听歌单长按歌曲：从常听中移除该曲目
    var removeTrack by remember { mutableStateOf<MusicTrack?>(null) }
    TracksContent(
        tracks = tracks,
        source = source,
        playbackState = playbackState,
        trailingAction = {},
        onTrackLongClick = { track ->
            if (type == SmartPlaylistType.RECENT) removeTrack = track
        },
        reorderEnabled = type == SmartPlaylistType.FAVORITE,
        onReorder = reorder,
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

// 曲目页主体：播放全部 + 操作栏 + 可拖拽列表。
// 不可重排时不挂手柄，否则下次解析回滚
@Composable
internal fun TracksContent(
    tracks: List<MusicTrack>,
    source: PlaylistSource?,
    playbackState: MusicPlaybackState,
    trailingAction: @Composable () -> Unit,
    onTrackLongClick: (MusicTrack) -> Unit,
    reorderEnabled: Boolean = true,
    onReorder: ((List<MusicTrack>) -> Unit)? = null,
) {
    val context = LocalContext.current
    val metadataEnricher = LocalMetadataEnricher.current
    val listState = rememberLazyListState()
    // 本地可排序列表，数据源变化时重置
    var orderedTracks by remember(tracks) { mutableStateOf(tracks) }
    // 封面预取，进入视口不闪占位符
    CoverPrefetch(listState) { index -> orderedTracks.getOrNull(index) }
    // 拖拽项跟手，其余 animateItem 让位
    val reorderableState = rememberReorderableLazyListState(listState) { from, to ->
        val list = orderedTracks.toMutableList()
        val moved = list.removeAt(from)
        list.add(to, moved)
        orderedTracks = list
        // 与当前过滤来源一致时实时同步播放结果顺序
        if (playbackState.queueFilter?.key == source?.key) {
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
                onClick = { playQueue(context, playbackState, metadataEnricher, orderedTracks, 0, source) },
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
                text = pluralStringResource(R.plurals.music_panel_track_count, orderedTracks.size, orderedTracks.size),
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
                                    playQueue(context, playbackState, metadataEnricher, orderedTracks, index, source)
                                }
                            },
                            onLongClick = { onTrackLongClick(track) },
                            onFavoriteClick = { playbackState.toggleFavorite(track.id) },
                            dragHandleModifier = if (reorderEnabled) {
                                Modifier.longPressDraggableHandle(reorderableState, track.audioUri)
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }
    }
}

// 空态提示：居中一行浅色文字
@Composable
internal fun EmptyHint(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 12.sp,
        )
    }
}

// 设队列过滤并切入指定曲目
private fun playQueue(
    context: android.content.Context,
    state: MusicPlaybackState,
    metadataEnricher: MetadataEnricher,
    tracks: List<MusicTrack>,
    startIndex: Int,
    source: PlaylistSource?,
) {
    if (tracks.isEmpty()) return
    val startTrackId = tracks[startIndex.coerceIn(0, tracks.lastIndex)].id
    switchToPlaylistQueue(
        context = context,
        state = state,
        tracks = tracks,
        source = source,
        metadataEnricher = metadataEnricher,
        startTrackId = startTrackId,
        autoPlay = true,
    )
}
