package com.yichao.evilgodxu.screens.home.component.playlist.expanded

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.data.playlist.Playlist
import com.yichao.evilgodxu.data.playlist.PlaylistGroup
import com.yichao.evilgodxu.data.playlist.PlaylistStore
import com.yichao.evilgodxu.data.playlist.SmartPlaylistType
import com.yichao.evilgodxu.data.playlist.artistGroup
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.screens.home.component.playlist.PlaylistAlbumTracksPage
import com.yichao.evilgodxu.screens.home.component.playlist.PlaylistArtistTracksPage
import com.yichao.evilgodxu.screens.home.component.playlist.PlaylistGroupsPage
import com.yichao.evilgodxu.screens.home.component.playlist.PlaylistSmartTracksPage
import com.yichao.evilgodxu.screens.home.component.playlist.PlaylistTracksPage
import com.yichao.evilgodxu.ui.component.smartTypeLabel
import com.yichao.evilgodxu.ui.icons.AppIcons

// 左栏宽度：平板横屏下既容得下歌单名与计数，又不挤占右栏内容
private val PlaylistRailWidth = 300.dp

// 宽屏歌单面板：左栏常驻导航 + 右栏所选类目的内容。横向宽度在此即导航，点选直接换右栏，
// 不设整屏沉浸页；仅专辑/歌手分组列表的下钻在右栏内保留一层回退
@Composable
internal fun PlaylistWidePanel(
    playbackState: MusicPlaybackState,
    playlistStore: PlaylistStore,
    menuBackgroundColor: Color,
    // 标题栏高度：本面板的内容都从标题栏下方开始
    topInset: Dp,
    onImmersivePageChange: (Boolean) -> Unit,
    pendingArtist: String?,
    onPendingArtistHandled: () -> Unit,
    pendingCreated: Playlist?,
    onPendingCreatedHandled: () -> Unit,
    onCreatePlaylist: () -> Unit,
    onRename: (Playlist) -> Unit,
    onDelete: (Playlist) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 左栏当前选中项，右栏内容随之切换；切项即清空右栏内的下钻
    var destination by remember {
        mutableStateOf<PlaylistDestination>(PlaylistDestination.Smart(SmartPlaylistType.RECENT))
    }
    // 右栏内的一层下钻：从专辑/歌手分组列表进入该分组的曲目
    var drilledGroup by remember { mutableStateOf<PlaylistGroup?>(null) }
    // 宽屏面板无整屏沉浸页：标题栏始终在场，不为本面板让位
    LaunchedEffect(Unit) { onImmersivePageChange(false) }
    // 点击播放器歌手信息直达该歌手：选中歌手类目并直接下钻到该歌手
    LaunchedEffect(pendingArtist) {
        val artist = pendingArtist?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        destination = PlaylistDestination.Smart(SmartPlaylistType.ARTIST)
        drilledGroup = artistGroup(playbackState.libraryTracks, artist)
        onPendingArtistHandled()
    }
    // 新建成功后切到该歌单，便于立即添加歌曲
    LaunchedEffect(pendingCreated) {
        val created = pendingCreated ?: return@LaunchedEffect
        destination = PlaylistDestination.Custom(created)
        drilledGroup = null
        onPendingCreatedHandled()
    }
    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(top = topInset),
    ) {
        PlaylistRail(
            playbackState = playbackState,
            playlistStore = playlistStore,
            selected = destination,
            onSelect = { selected ->
                destination = selected
                drilledGroup = null
            },
            onCreatePlaylist = onCreatePlaylist,
            onRename = onRename,
            onDelete = onDelete,
            menuBackgroundColor = menuBackgroundColor,
            modifier = Modifier
                .width(PlaylistRailWidth)
                .fillMaxHeight(),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            PlaylistDetailPane(
                playlistStore = playlistStore,
                destination = destination,
                drilledGroup = drilledGroup,
                playbackState = playbackState,
                onOpenGroup = { group -> drilledGroup = group },
                onBackFromGroup = { drilledGroup = null },
            )
        }
    }
}

// 左栏可选目的地：系统歌单（常听/收藏/专辑/歌手）与自定义歌单
internal sealed interface PlaylistDestination {
    data class Smart(val type: SmartPlaylistType) : PlaylistDestination
    data class Custom(val playlist: Playlist) : PlaylistDestination
}

// 右栏内容：分组下钻优先于左栏所选类目，其余按类目分发到既有页面
@Composable
private fun PlaylistDetailPane(
    playlistStore: PlaylistStore,
    destination: PlaylistDestination,
    drilledGroup: PlaylistGroup?,
    playbackState: MusicPlaybackState,
    onOpenGroup: (PlaylistGroup) -> Unit,
    onBackFromGroup: () -> Unit,
) {
    if (drilledGroup != null) {
        Column(modifier = Modifier.fillMaxSize()) {
            WideDetailHeader(title = drilledGroup.name, showBack = true, onBack = onBackFromGroup)
            if (drilledGroup.key.startsWith("album:")) {
                PlaylistAlbumTracksPage(group = drilledGroup, playbackState = playbackState)
            } else {
                PlaylistArtistTracksPage(group = drilledGroup, playbackState = playbackState)
            }
        }
        return
    }
    when (destination) {
        is PlaylistDestination.Smart -> when (destination.type) {
            SmartPlaylistType.RECENT, SmartPlaylistType.FAVORITE ->
                Column(modifier = Modifier.fillMaxSize()) {
                    WideDetailHeader(title = smartTypeLabel(destination.type), showBack = false, onBack = {})
                    PlaylistSmartTracksPage(type = destination.type, playbackState = playbackState)
                }
            SmartPlaylistType.ALBUM, SmartPlaylistType.ARTIST ->
                Column(modifier = Modifier.fillMaxSize()) {
                    WideDetailHeader(title = smartTypeLabel(destination.type), showBack = false, onBack = {})
                    PlaylistGroupsPage(
                        type = destination.type,
                        playbackState = playbackState,
                        onOpenGroup = onOpenGroup,
                    )
                }
        }
        is PlaylistDestination.Custom -> {
            // 歌单可能刚被重命名：标题与内容都按当前存储里的那份取名
            val current = playlistStore.playlists.find { it.id == destination.playlist.id } ?: destination.playlist
            Column(modifier = Modifier.fillMaxSize()) {
                WideDetailHeader(title = current.name, showBack = false, onBack = {})
                PlaylistTracksPage(playlist = current, playbackState = playbackState)
            }
        }
    }
}

// 右栏标题栏：承载当前内容的名字；下钻进入分组曲目时提供一层返回
@Composable
private fun WideDetailHeader(
    title: String,
    showBack: Boolean,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (showBack) 4.dp else 16.dp, end = 16.dp, top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showBack) {
            IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = AppIcons.ChevronLeft,
                    contentDescription = stringResource(R.string.back),
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Text(
            text = title,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}