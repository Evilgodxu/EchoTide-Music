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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.data.music.api.MusicQuality
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.proxy.PlaylistSyncer
import com.yichao.evilgodxu.data.music.proxy.PlaylistSyncResult
import com.yichao.evilgodxu.data.music.proxy.RemotePlaylistLink
import com.yichao.evilgodxu.data.music.proxy.SyncFailure
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
import com.yichao.evilgodxu.LocalPlaylistRefresher
import com.yichao.evilgodxu.LocalPlaylistStore
import com.yichao.evilgodxu.screens.home.component.dialog.PlaylistImportDialog
import com.yichao.evilgodxu.ui.icons.AppIcons
import com.yichao.evilgodxu.ui.component.PlaylistArt
import com.yichao.evilgodxu.ui.component.smartTypeLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// 首页左滑呼出的歌单面板：系统歌单 + 自定义歌单，支持页面栈导航
@Composable
internal fun PlaylistPanel(
    visible: Boolean,
    playbackState: MusicPlaybackState,
    menuBackgroundColor: Color,
    // 点击播放器歌手信息后请求打开的歌手名：非空时跳转到该歌手的曲目列表
    pendingArtist: String? = null,
    onPendingArtistHandled: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val playlistStore = LocalPlaylistStore.current
    val playlistRefresher = LocalPlaylistRefresher.current
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
    var showImport by remember { mutableStateOf(false) }
    // 歌单同步后台状态：进度显示在总览标题区，完成提示短暂展示后自动清理
    var syncState by remember { mutableStateOf<SyncUiState?>(null) }
    var syncJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val importFailedMessage = stringResource(R.string.playlist_import_failed)
    val importFetchFailedMessage = stringResource(R.string.playlist_import_fetch_failed)
    val importEmptyMessage = stringResource(R.string.playlist_import_empty)

    fun startSync(link: RemotePlaylistLink, name: String, quality: MusicQuality) {
        syncJob?.cancel()
        syncState = SyncUiState.Running(0, 0, "")
        syncJob = scope.launch {
            val result = PlaylistSyncer.syncToLibrary(
                context, playbackState, link, quality, playlistRefresher,
            ) { done, total, title ->
                syncState = SyncUiState.Running(total, done, title)
            }
            syncState = when (result) {
                is PlaylistSyncResult.Success -> {
                    // 已存在同名歌单则并入做增量更新，仅无同名时才新建
                    val target = playlistStore.findOrCreateByName(context, name)
                    if (target != null) {
                        playlistStore.addTracks(context, target.id, result.trackIds)
                        SyncUiState.Finished(
                            result.stats.downloadedCount,
                            result.stats.existingCount,
                            result.stats.failedCount,
                        )
                    } else {
                        SyncUiState.Failed(importFailedMessage)
                    }
                }
                is PlaylistSyncResult.Failure -> SyncUiState.Failed(
                    when (result.reason) {
                        SyncFailure.FETCH_FAILED -> importFetchFailedMessage
                        SyncFailure.NO_DOWNLOAD -> importEmptyMessage
                        SyncFailure.LIBRARY_MATCH_FAILED -> importFailedMessage
                    }
                )
            }
            delay(SYNC_DONE_DISMISS_MS)
            syncState = null
            syncJob = null
        }
    }

    Box(modifier = modifier) {
        // 透明全屏布局，与在线搜索面板一致，透出首页沉浸渐变背景
        Column(modifier = Modifier.fillMaxSize()) {
            PanelHeader(
                title = if (page is PlaylistPage.Overview) syncTitle(syncState) else page.title(),
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
                    onImportPlaylist = { showImport = true },
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
    PlaylistImportDialog(
        visible = showImport,
        onSyncStart = { link, name, quality ->
            showImport = false
            // 同步在后台进行，进度显示在歌单面板标题区
            startSync(link, name, quality)
        },
        onDismiss = { showImport = false },
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
    onImportPlaylist: () -> Unit,
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
    LazyColumn(
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
                        countText = stringResource(R.string.music_panel_track_count, recentCount),
                        onClick = { onOpenSmart(SmartPlaylistType.RECENT) },
                        modifier = Modifier.weight(1f),
                    )
                    SmartPlaylistCard(
                        type = SmartPlaylistType.FAVORITE,
                        countText = stringResource(R.string.music_panel_track_count, favoriteCount),
                        onClick = { onOpenSmart(SmartPlaylistType.FAVORITE) },
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SmartPlaylistCard(
                        type = SmartPlaylistType.ALBUM,
                        countText = stringResource(R.string.playlist_album_count, albumCount),
                        onClick = { onOpenSmart(SmartPlaylistType.ALBUM) },
                        modifier = Modifier.weight(1f),
                        coverTrack = firstAlbumCover,
                    )
                    SmartPlaylistCard(
                        type = SmartPlaylistType.ARTIST,
                        countText = stringResource(R.string.playlist_artist_count, artistCount),
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
            ImportPlaylistRow(onClick = onImportPlaylist)
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
    var menuExpanded by remember { mutableStateOf(false) }
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
                text = stringResource(R.string.music_panel_track_count, count),
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 10.sp,
            )
        }
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(
                    imageVector = AppIcons.MoreVert,
                    contentDescription = stringResource(R.string.playlist_more),
                    tint = Color.White,
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                containerColor = menuBackgroundColor,
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.playlist_rename), color = Color.White) },
                    onClick = {
                        menuExpanded = false
                        onRename()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.playlist_delete), color = Color.White) },
                    onClick = {
                        menuExpanded = false
                        onDelete()
                    },
                )
            }
        }
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

// 从平台分享链接导入歌单入口：样式与新建歌单卡片一致，纯文字无图标
@Composable
private fun ImportPlaylistRow(onClick: () -> Unit) {
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
            text = stringResource(R.string.playlist_import),
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

// 歌单同步状态：运行中实时进度 / 已完成统计 / 失败原因
private sealed interface SyncUiState {
    data class Running(val total: Int, val done: Int, val currentTitle: String) : SyncUiState
    data class Finished(val success: Int, val existing: Int, val failed: Int) : SyncUiState
    data class Failed(val message: String) : SyncUiState
}

// 总览页标题区文案：同步进行中显示进度，完成后显示统计
@Composable
private fun syncTitle(syncState: SyncUiState?): String = when (syncState) {
    is SyncUiState.Running ->
        if (syncState.total > 0) {
            stringResource(R.string.playlist_import_progress, syncState.done, syncState.total, syncState.currentTitle)
        } else {
            stringResource(R.string.playlist_import_preparing)
        }
    is SyncUiState.Finished ->
        stringResource(R.string.playlist_import_done, syncState.success, syncState.existing, syncState.failed)
    is SyncUiState.Failed -> syncState.message
    null -> ""
}

// 同步完成提示的展示时长
private const val SYNC_DONE_DISMISS_MS = 3_000L
