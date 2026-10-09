package com.yichao.evilgodxu.screens.home.component.playlist

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.playlist.Playlist
import com.yichao.evilgodxu.data.playlist.PlaylistGroup
import com.yichao.evilgodxu.data.playlist.PlaylistStore
import com.yichao.evilgodxu.data.playlist.SmartPlaylistType
import com.yichao.evilgodxu.data.playlist.albumGroups
import com.yichao.evilgodxu.data.playlist.artistGroup
import com.yichao.evilgodxu.data.playlist.artistGroups
import com.yichao.evilgodxu.data.playlist.distinctAlbumCount
import com.yichao.evilgodxu.data.playlist.distinctArtistCount
import com.yichao.evilgodxu.data.playlist.smartTrackCount
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.LocalPlaylistStore
import com.yichao.evilgodxu.ui.icons.AppIcons
import com.yichao.evilgodxu.ui.component.CoverPrefetch
import com.yichao.evilgodxu.ui.component.ExpandDirection
import com.yichao.evilgodxu.ui.component.ExpandPicker
import com.yichao.evilgodxu.ui.component.PlaylistArt
import com.yichao.evilgodxu.ui.component.smartTypeLabel

// 首页左滑呼出的歌单面板：系统歌单 + 自定义歌单，支持页面栈导航
@Composable
internal fun PlaylistPanel(
    visible: Boolean,
    playbackState: MusicPlaybackState,
    menuBackgroundColor: Color,
    modifier: Modifier = Modifier,
    // 点击播放器歌手信息后请求打开的歌手名：非空时跳转到该歌手的曲目列表
    pendingArtist: String? = null,
    onPendingArtistHandled: () -> Unit = {},
) {
    val context = LocalContext.current
    val playlistStore = LocalPlaylistStore.current
    // 读盘切到 IO：首次 getSharedPreferences 需同步解析整份歌单 JSON，在主线程执行会阻塞首帧
    LaunchedEffect(Unit) { playlistStore.awaitLoaded(context) }
    var backStack by remember { mutableStateOf(listOf<PlaylistPage>(PlaylistPage.Overview)) }
    LaunchedEffect(visible) { if (!visible) backStack = listOf(PlaylistPage.Overview) }
    // 点击歌手信息直达该歌手的曲目列表：与艺术家分组页同构，保留总览页作为回退目标
    LaunchedEffect(pendingArtist) {
        val artist = pendingArtist?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        backStack = listOf(
            PlaylistPage.Overview,
            PlaylistPage.GroupTracks(SmartPlaylistType.ARTIST, artistGroup(playbackState.libraryTracks, artist)),
        )
        onPendingArtistHandled()
    }
    val page = backStack.last()
    // 二级/三级详情页系统返回键逐级回退；顶层页面由首页 BackHandler 关闭面板
    BackHandler(enabled = visible && backStack.size > 1) {
        backStack = backStack.dropLast(1)
    }
    // 新建/重命名/删除歌单弹窗
    var showCreate by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Playlist?>(null) }
    var deleteTarget by remember { mutableStateOf<Playlist?>(null) }

    Box(modifier = modifier) {
        // 透明全屏布局，与在线搜索面板一致，透出首页沉浸渐变背景
        Column(modifier = Modifier.fillMaxSize()) {
            PanelHeader(
                title = page.title(),
                showBack = backStack.size > 1,
                onBack = { backStack = backStack.dropLast(1) },
            )
            when (page) {
                is PlaylistPage.Overview -> PlaylistOverview(
                    playlistStore = playlistStore,
                    playbackState = playbackState,
                    menuBackgroundColor = menuBackgroundColor,
                    onOpenSmart = { type ->
                        backStack = backStack + if (type == SmartPlaylistType.ALBUM || type == SmartPlaylistType.ARTIST) {
                            PlaylistPage.Groups(type)
                        } else {
                            PlaylistPage.SmartTracks(type)
                        }
                    },
                    onOpenCustom = { playlist -> backStack = backStack + PlaylistPage.Tracks(playlist) },
                    onCreatePlaylist = { showCreate = true },
                    onRename = { renameTarget = it },
                    onDelete = { deleteTarget = it },
                )
                is PlaylistPage.Groups -> PlaylistGroupsPage(
                    type = page.type,
                    playbackState = playbackState,
                    onOpenGroup = { group -> backStack = backStack + PlaylistPage.GroupTracks(page.type, group) },
                )
                is PlaylistPage.SmartTracks -> PlaylistSmartTracksPage(
                    type = page.type,
                    playbackState = playbackState,
                )
                is PlaylistPage.Tracks -> PlaylistTracksPage(playlist = page.playlist, playbackState = playbackState)
                is PlaylistPage.GroupTracks -> PlaylistGroupTracksPage(
                    type = page.type,
                    group = page.group,
                    playbackState = playbackState,
                )
            }
        }
    }
    CreatePlaylistDialog(
        visible = showCreate,
        onCreated = { playlist ->
            showCreate = false
            // 创建后直接进入新歌单，便于立即添加歌曲
            backStack = backStack + PlaylistPage.Tracks(playlist)
        },
        onDismiss = { showCreate = false },
    )
    RenamePlaylistDialog(playlist = renameTarget, onDismiss = { renameTarget = null })
    DeletePlaylistDialog(playlist = deleteTarget, onDismiss = { deleteTarget = null })
}

// 面板页面：总览 / 智能分组列表 / 智能曲目 / 自定义歌单曲目 / 智能分组曲目
private sealed interface PlaylistPage {
    data object Overview : PlaylistPage
    data class Groups(val type: SmartPlaylistType) : PlaylistPage
    data class SmartTracks(val type: SmartPlaylistType) : PlaylistPage
    data class Tracks(val playlist: Playlist) : PlaylistPage
    data class GroupTracks(val type: SmartPlaylistType, val group: PlaylistGroup) : PlaylistPage
}

@Composable
private fun PlaylistPage.title(): String = when (this) {
    is PlaylistPage.Overview -> ""
    is PlaylistPage.Groups -> smartTypeLabel(type)
    is PlaylistPage.SmartTracks -> smartTypeLabel(type)
    is PlaylistPage.Tracks -> playlist.name
    is PlaylistPage.GroupTracks -> group.name
}

// 面板顶部栏：返回按钮 + 标题，关闭统一走系统返回键
@Composable
private fun PanelHeader(
    title: String,
    showBack: Boolean,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showBack) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = AppIcons.ChevronLeft,
                    contentDescription = stringResource(R.string.back),
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Text(
            text = title,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

// 总览页：系统歌单卡片 + 我的歌单列表 + 新建歌单入口
@Composable
private fun PlaylistOverview(
    playlistStore: PlaylistStore,
    playbackState: MusicPlaybackState,
    menuBackgroundColor: Color,
    onOpenSmart: (SmartPlaylistType) -> Unit,
    onOpenCustom: (Playlist) -> Unit,
    onCreatePlaylist: () -> Unit,
    onRename: (Playlist) -> Unit,
    onDelete: (Playlist) -> Unit,
) {
    val allTracks = playbackState.libraryTracks
    // 本面板常驻合成树（仅靠位移移出屏幕，不做可见性短路），全库分组与计数必须缓存：
    // 否则每次重组都要重扫全库，扫描期的封面批量回写会把它放大成持续卡顿
    val unknownAlbum = stringResource(R.string.playlist_unknown_album)
    val unknownArtist = stringResource(R.string.music_scanner_unknown_artist)
    val libraryById = remember(allTracks) { allTracks.associateBy { it.id } }
    // 专辑/艺术家入口封面取各自类目内第一首歌，避免显示全部库（播放队列）首曲
    val firstAlbumCover = remember(allTracks, unknownAlbum) {
        albumGroups(allTracks, unknownAlbum).firstOrNull()?.trackIds?.firstOrNull()
    }?.let { id -> libraryById[id] }
    val firstArtistCover = remember(allTracks, unknownArtist) {
        artistGroups(allTracks, unknownArtist).firstOrNull()?.trackIds?.firstOrNull()
    }?.let { id -> libraryById[id] }
    val recentCount = remember(allTracks, playbackState.recentPlayedIds) {
        smartTrackCount(allTracks, playbackState.recentPlayedIds)
    }
    val favoriteCount = remember(allTracks, playbackState.likedIds) {
        smartTrackCount(allTracks, playbackState.likedIds)
    }
    val albumCount = remember(allTracks) { distinctAlbumCount(allTracks) }
    val artistCount = remember(allTracks) { distinctArtistCount(allTracks) }
    val listState = rememberLazyListState()
    // 歌单行封面取歌单首曲：可视区邻域提前取图，滚动/曲库回填后不再先闪占位符。
    // 歌单行由 [OVERVIEW_HEADER_ITEM_COUNT] 之后开始，故列表下标需先扣除头部项
    CoverPrefetch(listState) { index ->
        playlistStore.playlists.getOrNull(index - OVERVIEW_HEADER_ITEM_COUNT)
            ?.trackIds
            ?.firstNotNullOfOrNull { libraryById[it] }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item {
            SectionLabel(text = stringResource(R.string.playlist_section_smart))
            Spacer(modifier = Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SmartPlaylistCard(
                        type = SmartPlaylistType.RECENT,
                        countText = pluralStringResource(R.plurals.music_panel_track_count, recentCount, recentCount),
                        onClick = { onOpenSmart(SmartPlaylistType.RECENT) },
                        modifier = Modifier.weight(1f),
                    )
                    SmartPlaylistCard(
                        type = SmartPlaylistType.FAVORITE,
                        countText = pluralStringResource(R.plurals.music_panel_track_count, favoriteCount, favoriteCount),
                        onClick = { onOpenSmart(SmartPlaylistType.FAVORITE) },
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SmartPlaylistCard(
                        type = SmartPlaylistType.ALBUM,
                        countText = pluralStringResource(R.plurals.playlist_album_count, albumCount, albumCount),
                        onClick = { onOpenSmart(SmartPlaylistType.ALBUM) },
                        modifier = Modifier.weight(1f),
                        coverTrack = firstAlbumCover,
                    )
                    SmartPlaylistCard(
                        type = SmartPlaylistType.ARTIST,
                        countText = pluralStringResource(R.plurals.playlist_artist_count, artistCount, artistCount),
                        onClick = { onOpenSmart(SmartPlaylistType.ARTIST) },
                        modifier = Modifier.weight(1f),
                        coverTrack = firstArtistCover,
                    )
                }
            }
        }
        item {
            Spacer(modifier = Modifier.height(12.dp))
            SectionLabel(text = stringResource(R.string.playlist_section_my))
            Spacer(modifier = Modifier.height(6.dp))
        }
        item {
            Spacer(modifier = Modifier.height(8.dp))
            CreatePlaylistRow(onClick = onCreatePlaylist)
            Spacer(modifier = Modifier.height(8.dp))
        }
        items(playlistStore.playlists, key = { it.id }) { playlist ->
            // 逐行缓存解析结果：自定义歌单数量与库大小无关地重复查表会形成 O(歌单数 × 库大小)
            val playlistTracks = remember(playlist.trackIds, libraryById) {
                playlist.trackIds.mapNotNull { libraryById[it] }
            }
            PlaylistListRow(
                playlist = playlist,
                count = playlistTracks.size,
                coverTrack = playlistTracks.firstOrNull(),
                menuBackgroundColor = menuBackgroundColor,
                onClick = { onOpenCustom(playlist) },
                onRename = { onRename(playlist) },
                onDelete = { onDelete(playlist) },
            )
        }
        item {
            Spacer(modifier = Modifier.height(14.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
    )
}

// 系统歌单卡片：白色描边圆角卡片 + 白色图标与文字，与在线搜索输入框风格一致
@Composable
private fun SmartPlaylistCard(
    type: SmartPlaylistType,
    countText: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    coverTrack: MusicTrack? = null,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.45f),
                shape = RoundedCornerShape(24.dp),
            )
            .clickable(onClick = onClick)
            .padding(14.dp),
    ) {
        if (coverTrack != null) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(6.dp))
            ) {
                PlaylistArt(track = coverTrack, modifier = Modifier.fillMaxSize())
            }
        } else {
            Icon(
                imageVector = smartTypeIcon(type),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = smartTypeLabel(type),
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = countText,
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 11.sp,
        )
    }
}

// 自定义歌单行的操作项：重命名 / 删除
private enum class PlaylistRowAction(val labelRes: Int) {
    Rename(R.string.playlist_rename),
    Delete(R.string.playlist_delete),
}

// 自定义歌单行：无背景 + 小圆角 + 封面或图标与主次文字，对齐在线搜索结果行
@Composable
private fun PlaylistListRow(
    playlist: Playlist,
    count: Int,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    menuBackgroundColor: Color,
    coverTrack: MusicTrack? = null,
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
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = playlist.name,
                color = Color.White,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = pluralStringResource(R.plurals.music_panel_track_count, count, count),
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 10.sp,
            )
        }
        ExpandPicker(
            options = PlaylistRowAction.entries,
            selected = null,
            expandDirection = ExpandDirection.Down,
            // 面板靠右缘，菜单右对齐避免溢出屏幕
            horizontalAlignment = Alignment.End,
            containerColor = menuBackgroundColor,
            trigger = {
                Box(
                    modifier = Modifier.size(40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = AppIcons.MoreVert,
                        contentDescription = stringResource(R.string.playlist_more),
                        tint = Color.White,
                    )
                }
            },
            itemContent = { action, _ ->
                Text(
                    text = stringResource(action.labelRes),
                    color = Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            },
            onItemClick = { action ->
                when (action) {
                    PlaylistRowAction.Rename -> onRename()
                    PlaylistRowAction.Delete -> onDelete()
                }
            },
        )
    }
}

// 新建歌单入口：白色描边圆角卡片，与系统歌单卡片一致
@Composable
private fun CreatePlaylistRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.45f),
                shape = RoundedCornerShape(24.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.playlist_create),
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun smartTypeIcon(type: SmartPlaylistType): ImageVector = when (type) {
    SmartPlaylistType.RECENT -> AppIcons.History
    SmartPlaylistType.FAVORITE -> AppIcons.Favorite
    SmartPlaylistType.ALBUM -> AppIcons.Album
    SmartPlaylistType.ARTIST -> AppIcons.Person
}

// 总览列表里歌单行之前的头部项数：系统歌单卡片、我的歌单分节标题、新建入口各占一项。
// 预取按列表下标解析歌单时须按此回退，新增头部项时同步调整
private const val OVERVIEW_HEADER_ITEM_COUNT = 3
