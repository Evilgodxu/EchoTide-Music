package com.yichao.evilgodxu.screens.home.component.playlist

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.playlist.Playlist
import com.yichao.evilgodxu.data.playlist.PlaylistGroup
import com.yichao.evilgodxu.data.playlist.PlaylistStore
import com.yichao.evilgodxu.ui.gesture.swipeBack
import com.yichao.evilgodxu.ui.gesture.swipeForward
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
import com.yichao.evilgodxu.navigation.LocalIsTopLayer
import com.yichao.evilgodxu.screens.home.component.playlist.expanded.PlaylistWidePanel
import com.yichao.evilgodxu.theme.AppMotion
import com.yichao.evilgodxu.ui.component.CoverPrefetch
import com.yichao.evilgodxu.ui.component.ExpandDirection
import com.yichao.evilgodxu.ui.component.ExpandPicker
import com.yichao.evilgodxu.ui.component.PlaylistArt
import com.yichao.evilgodxu.ui.component.smartTypeLabel
import com.yichao.evilgodxu.ui.icons.AppIcons
import com.yichao.evilgodxu.windowsize.rememberExpandedForm

// 歌单面板：窄屏逐级下钻，宽屏左右分栏
// 共享的存储与增删改弹窗收在此处。
@Composable
internal fun PlaylistPanel(
    visible: Boolean,
    playbackState: MusicPlaybackState,
    menuBackgroundColor: Color,
    // 标题栏高度，非歌手页内容下移
    topInset: Dp,
    // 标题栏是否让位，随页面栈上报
    onImmersivePageChange: (Boolean) -> Unit,
    // 是否接管横滑，随页面栈上报
    onSwipeBackChange: (Boolean) -> Unit,
    // 关闭面板回播放器；歌手页直达时直接回
    onClose: () -> Unit,
    // 总览页继续左滑进入设置页
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    // 待打开的歌手名，非空时跳转
    pendingArtist: String? = null,
    onPendingArtistHandled: () -> Unit = {},
) {
    val playlistStore = LocalPlaylistStore.current
    // 新建/重命名/删除歌单弹窗
    var showCreate by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Playlist?>(null) }
    var deleteTarget by remember { mutableStateOf<Playlist?>(null) }
    // 新建后待进入的歌单，两形态各自消费
    var createdPlaylist by remember { mutableStateOf<Playlist?>(null) }

    Box(modifier = modifier) {
        if (rememberExpandedForm()) {
            PlaylistWidePanel(
                visible = visible,
                playbackState = playbackState,
                playlistStore = playlistStore,
                menuBackgroundColor = menuBackgroundColor,
                topInset = topInset,
                onImmersivePageChange = onImmersivePageChange,
                onSwipeBackChange = onSwipeBackChange,
                pendingArtist = pendingArtist,
                onPendingArtistHandled = onPendingArtistHandled,
                pendingCreated = createdPlaylist,
                onPendingCreatedHandled = { createdPlaylist = null },
                onCreatePlaylist = { showCreate = true },
                onRename = { renameTarget = it },
                onDelete = { deleteTarget = it },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            PlaylistStackedPanel(
                visible = visible,
                playbackState = playbackState,
                playlistStore = playlistStore,
                menuBackgroundColor = menuBackgroundColor,
                topInset = topInset,
                onImmersivePageChange = onImmersivePageChange,
                onSwipeBackChange = onSwipeBackChange,
                onClose = onClose,
                onOpenSettings = onOpenSettings,
                pendingArtist = pendingArtist,
                onPendingArtistHandled = onPendingArtistHandled,
                pendingCreated = createdPlaylist,
                onPendingCreatedHandled = { createdPlaylist = null },
                onCreatePlaylist = { showCreate = true },
                onRename = { renameTarget = it },
                onDelete = { deleteTarget = it },
            )
        }
    }
    CreatePlaylistDialog(
        visible = showCreate,
        // 创建后直接进入，便于添加歌曲
        onCreated = { playlist ->
            showCreate = false
            createdPlaylist = playlist
        },
        onDismiss = { showCreate = false },
    )
    RenamePlaylistDialog(playlist = renameTarget, onDismiss = { renameTarget = null })
    DeletePlaylistDialog(playlist = deleteTarget, onDismiss = { deleteTarget = null })
}

// 窄屏歌单面板：标题栏 + 页面栈逐级下钻。
// 歌手与专辑页自带整屏骨架，标题栏让位。
@Composable
private fun PlaylistStackedPanel(
    visible: Boolean,
    playbackState: MusicPlaybackState,
    playlistStore: PlaylistStore,
    menuBackgroundColor: Color,
    topInset: Dp,
    onImmersivePageChange: (Boolean) -> Unit,
    onSwipeBackChange: (Boolean) -> Unit,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
    pendingArtist: String?,
    onPendingArtistHandled: () -> Unit,
    pendingCreated: Playlist?,
    onPendingCreatedHandled: () -> Unit,
    onCreatePlaylist: () -> Unit,
    onRename: (Playlist) -> Unit,
    onDelete: (Playlist) -> Unit,
) {
    var backStack by remember { mutableStateOf(listOf<PlaylistPage>(PlaylistPage.Overview)) }
    // 页面栈唯一入口：栈、标题栏与横滑须同次写入
    fun go(stack: List<PlaylistPage>) {
        backStack = stack
        onImmersivePageChange(stack.last().immersiveGroupPage != null)
        onSwipeBackChange(stack.size > 1)
    }
    LaunchedEffect(visible) { if (!visible) go(listOf(PlaylistPage.Overview)) }
    // 歌手直达该歌手曲目列表，回退直接回播放器
    LaunchedEffect(pendingArtist) {
        val artist = pendingArtist?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        go(
            listOf(
                PlaylistPage.Overview,
                PlaylistPage.GroupTracks(SmartPlaylistType.ARTIST, artistGroup(playbackState.libraryTracks, artist)),
            )
        )
        onPendingArtistHandled()
    }
    // 新建成功后进入该歌单，便于立即添加歌曲
    LaunchedEffect(pendingCreated) {
        val created = pendingCreated ?: return@LaunchedEffect
        go(backStack + PlaylistPage.Tracks(created))
        onPendingCreatedHandled()
    }
    // 回退统一出口；播放器直达歌手页时上一级就是播放器，直接关面板
    fun goBack() {
        val last = backStack.lastOrNull()
        val openedFromPlayer = backStack.size == 2 &&
            last is PlaylistPage.GroupTracks &&
            last.type == SmartPlaylistType.ARTIST
        if (openedFromPlayer) {
            onClose()
            return
        }
        if (backStack.size > 1) go(backStack.dropLast(1))
    }
    // 返回键逐级回退；被压住时让出
    BackHandler(enabled = LocalIsTopLayer.current && visible && backStack.size > 1) {
        goBack()
    }
    // 右滑回退与返回键同出口，有上级才生效
    val backSwipe = Modifier.swipeBack(enabled = { visible && backStack.size > 1 }, onBack = { goBack() })
    // 只在总览层生效，下钻层的左滑归内容
    val forwardSwipe = Modifier.swipeForward(
        enabled = { visible && backStack.size == 1 },
        onForward = onOpenSettings,
    )

    // 下钻右入左让，回退右出左补；两页同速整宽，不叠放
    AnimatedContent(
        targetState = backStack,
        transitionSpec = {
            val forward = targetState.size > initialState.size
            val spec = tween<IntOffset>(AppMotion.Standard)
            if (forward) {
                slideInHorizontally(animationSpec = spec) { it } togetherWith
                    slideOutHorizontally(animationSpec = spec) { -it }
            } else {
                slideInHorizontally(animationSpec = spec) { -it } togetherWith
                    slideOutHorizontally(animationSpec = spec) { it }
            }
        },
        modifier = Modifier.fillMaxSize(),
        label = "playlist_page",
    ) { stack ->
        val page = stack.last()
        // 歌手/专辑页：非空即在场，标题栏让位、不套内边距
        val immersiveGroup = page.immersiveGroupPage
        if (immersiveGroup != null) {
            // 自带整屏骨架，头图铺到标题栏后方，不套顶部内边距
            if (immersiveGroup.type == SmartPlaylistType.ARTIST) {
                ArtistPlaylistPage(
                    group = immersiveGroup.group,
                    playbackState = playbackState,
                    onOpenAlbum = { album ->
                        go(backStack + PlaylistPage.GroupTracks(SmartPlaylistType.ALBUM, album))
                    },
                    // 「歌曲」标签右滑与返回键同出口
                    onSwipeBack = { goBack() },
                )
            } else {
                AlbumPlaylistPage(
                    group = immersiveGroup.group,
                    playbackState = playbackState,
                    // 歌曲标签已无分页去向，右滑回退
                    onSwipeBack = { goBack() },
                )
            }
        } else {
            // 透明全屏，透出首页沉浸背景
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = topInset)
                    .then(backSwipe)
                    .then(forwardSwipe),
            ) {
                PanelHeader(title = page.title())
                when (page) {
                    is PlaylistPage.Overview -> PlaylistOverview(
                        playlistStore = playlistStore,
                        playbackState = playbackState,
                        menuBackgroundColor = menuBackgroundColor,
                        onOpenSmart = { type ->
                            go(
                                backStack + if (type == SmartPlaylistType.ALBUM || type == SmartPlaylistType.ARTIST) {
                                    PlaylistPage.Groups(type)
                                } else {
                                    PlaylistPage.SmartTracks(type)
                                }
                            )
                        },
                        onOpenCustom = { playlist -> go(backStack + PlaylistPage.Tracks(playlist)) },
                        onCreatePlaylist = onCreatePlaylist,
                        onRename = onRename,
                        onDelete = onDelete,
                    )
                    is PlaylistPage.Groups -> PlaylistGroupsPage(
                        type = page.type,
                        playbackState = playbackState,
                        onOpenGroup = { group -> go(backStack + PlaylistPage.GroupTracks(page.type, group)) },
                    )
                    is PlaylistPage.SmartTracks -> PlaylistSmartTracksPage(
                        type = page.type,
                        playbackState = playbackState,
                    )
                    is PlaylistPage.Tracks -> PlaylistTracksPage(
                        playlist = page.playlist,
                        playbackState = playbackState,
                    )
                    // 分组页已由上方分支接管，此处仅穷举
                    is PlaylistPage.GroupTracks -> Unit
                }
            }
        }
    }
}

// 面板页面：总览、分组、智能曲目、歌单与分组曲目
// 歌手与专辑分组走整屏骨架。
private sealed interface PlaylistPage {
    data object Overview : PlaylistPage
    data class Groups(val type: SmartPlaylistType) : PlaylistPage
    data class SmartTracks(val type: SmartPlaylistType) : PlaylistPage
    data class Tracks(val playlist: Playlist) : PlaylistPage
    data class GroupTracks(val type: SmartPlaylistType, val group: PlaylistGroup) : PlaylistPage
}

// 歌手/专辑分组页自带整屏骨架，标题栏恒隐藏；为空即不是
private val PlaylistPage.immersiveGroupPage: PlaylistPage.GroupTracks?
    get() = (this as? PlaylistPage.GroupTracks)?.takeIf {
        it.type == SmartPlaylistType.ARTIST || it.type == SmartPlaylistType.ALBUM
    }

@Composable
private fun PlaylistPage.title(): String = when (this) {
    is PlaylistPage.Overview -> ""
    is PlaylistPage.Groups -> smartTypeLabel(type)
    is PlaylistPage.SmartTracks -> smartTypeLabel(type)
    is PlaylistPage.Tracks -> playlist.name
    is PlaylistPage.GroupTracks -> group.name
}

// 面板顶部栏只显示标题，返回交给右滑与返回键
@Composable
private fun PanelHeader(title: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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

// 计数：常听/收藏按曲目，专辑/歌手按分组
internal data class SmartPlaylistCounts(
    val recent: Int,
    val favorite: Int,
    val album: Int,
    val artist: Int,
)

// 总览与宽屏左栏共用，缓存到曲库或 id 表变化
@Composable
internal fun rememberSmartPlaylistCounts(playbackState: MusicPlaybackState): SmartPlaylistCounts {
    val library = playbackState.libraryTracks
    val recentIds = playbackState.recentPlayedIds
    val likedIds = playbackState.likedIds
    return remember(library, recentIds, likedIds) {
        SmartPlaylistCounts(
            recent = smartTrackCount(library, recentIds),
            favorite = smartTrackCount(library, likedIds),
            album = distinctAlbumCount(library),
            artist = distinctArtistCount(library),
        )
    }
}

// 总览页：系统卡片 + 我的歌单 + 新建入口
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
    val counts = rememberSmartPlaylistCounts(playbackState)
    // 面板常驻合成树，分组与计数必须缓存，否则重扫全库会持续卡顿
    val unknownAlbum = stringResource(R.string.playlist_unknown_album)
    val unknownArtist = stringResource(R.string.music_scanner_unknown_artist)
    val libraryById = remember(allTracks) { allTracks.associateBy { it.id } }
    // 入口封面取类目首曲，不用播放队列首曲
    val firstAlbumCover = remember(allTracks, unknownAlbum) {
        albumGroups(allTracks, unknownAlbum).firstOrNull()?.trackIds?.firstOrNull()
    }?.let { id -> libraryById[id] }
    val firstArtistCover = remember(allTracks, unknownArtist) {
        artistGroups(allTracks, unknownArtist).firstOrNull()?.trackIds?.firstOrNull()
    }?.let { id -> libraryById[id] }
    val listState = rememberLazyListState()
    // 封面取首曲并预取；下标须扣掉头部项
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
                        countText = pluralStringResource(R.plurals.music_panel_track_count, counts.recent, counts.recent),
                        onClick = { onOpenSmart(SmartPlaylistType.RECENT) },
                        modifier = Modifier.weight(1f),
                    )
                    SmartPlaylistCard(
                        type = SmartPlaylistType.FAVORITE,
                        countText = pluralStringResource(R.plurals.music_panel_track_count, counts.favorite, counts.favorite),
                        onClick = { onOpenSmart(SmartPlaylistType.FAVORITE) },
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SmartPlaylistCard(
                        type = SmartPlaylistType.ALBUM,
                        countText = pluralStringResource(R.plurals.playlist_album_count, counts.album, counts.album),
                        onClick = { onOpenSmart(SmartPlaylistType.ALBUM) },
                        modifier = Modifier.weight(1f),
                        coverTrack = firstAlbumCover,
                    )
                    SmartPlaylistCard(
                        type = SmartPlaylistType.ARTIST,
                        countText = pluralStringResource(R.plurals.playlist_artist_count, counts.artist, counts.artist),
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
            // 逐行缓存，避免 O(歌单数 × 库大小) 查表
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

// 分节标题，窄屏与宽屏左栏共用
@Composable
internal fun SectionLabel(text: String) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
    )
}

// 系统卡片：白色描边圆角 + 白色图标文字
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
internal enum class PlaylistRowAction(val labelRes: Int) {
    Rename(R.string.playlist_rename),
    Delete(R.string.playlist_delete),
}

// 歌单行：封面/图标 + 主次文字；窄屏与宽屏共用
@Composable
internal fun PlaylistListRow(
    playlist: Playlist,
    count: Int,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    menuBackgroundColor: Color,
    coverTrack: MusicTrack? = null,
    selected: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .then(if (selected) Modifier.background(Color.White.copy(alpha = 0.13f)) else Modifier)
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
            // 行高须显式给出，否则沿用 24sp 撑出空档
            Text(
                text = playlist.name,
                color = Color.White,
                fontSize = 12.sp,
                lineHeight = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = pluralStringResource(R.plurals.music_panel_track_count, count, count),
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 10.sp,
                lineHeight = 12.sp,
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

// 新建入口：白色描边圆角卡片，两处共用
@Composable
internal fun CreatePlaylistRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
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

// 歌单类型图标，窄屏与宽屏共用
internal fun smartTypeIcon(type: SmartPlaylistType): ImageVector = when (type) {
    SmartPlaylistType.RECENT -> AppIcons.History
    SmartPlaylistType.FAVORITE -> AppIcons.Favorite
    SmartPlaylistType.ALBUM -> AppIcons.Album
    SmartPlaylistType.ARTIST -> AppIcons.Person
}

// 歌单行前头部项数（3 项），新增头部项须同步
private const val OVERVIEW_HEADER_ITEM_COUNT = 3