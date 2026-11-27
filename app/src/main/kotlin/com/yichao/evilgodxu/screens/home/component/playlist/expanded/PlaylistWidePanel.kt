package com.yichao.evilgodxu.screens.home.component.playlist.expanded

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import com.yichao.evilgodxu.navigation.LocalIsTopLayer
import com.yichao.evilgodxu.screens.home.component.playlist.PlaylistAlbumSongsPage
import com.yichao.evilgodxu.screens.home.component.playlist.PlaylistArtistTracksPage
import com.yichao.evilgodxu.screens.home.component.playlist.PlaylistGroupsPage
import com.yichao.evilgodxu.screens.home.component.playlist.PlaylistSmartTracksPage
import com.yichao.evilgodxu.screens.home.component.playlist.PlaylistTracksPage
import com.yichao.evilgodxu.ui.component.smartTypeLabel
import com.yichao.evilgodxu.theme.AppMotion
import kotlinx.coroutines.delay

// 左栏宽 300dp，容纳名称与计数
private val PlaylistRailWidth = 300.dp

// 宽屏歌单：左栏导航 + 右栏内容，仅分组下钻保留一层回退
@Composable
internal fun PlaylistWidePanel(
    // 面板在场时才接管返回键的下钻回退
    visible: Boolean,
    playbackState: MusicPlaybackState,
    playlistStore: PlaylistStore,
    menuBackgroundColor: Color,
    // 标题栏高度，内容从下方开始
    topInset: Dp,
    onImmersivePageChange: (Boolean) -> Unit,
    // 本面板不接管横滑；切换形态时复位
    onSwipeBackChange: (Boolean) -> Unit,
    pendingArtist: String?,
    onPendingArtistHandled: () -> Unit,
    pendingCreated: Playlist?,
    onPendingCreatedHandled: () -> Unit,
    onCreatePlaylist: () -> Unit,
    onRename: (Playlist) -> Unit,
    onDelete: (Playlist) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 左栏选中项，切项清空下钻
    var destination by remember {
        mutableStateOf<PlaylistDestination>(PlaylistDestination.Smart(SmartPlaylistType.RECENT))
    }
    // 右栏下钻：分组列表进入曲目
    var drilledGroup by remember { mutableStateOf<PlaylistGroup?>(null) }
    // 下钻后返回先退分组列表，被覆盖层压住时让出
    BackHandler(enabled = LocalIsTopLayer.current && visible && drilledGroup != null) { drilledGroup = null }
    // 离开歌单页即退出下钻；等面板滑出视野再复位，免得退场半程露出列表
    LaunchedEffect(visible) {
        if (visible) return@LaunchedEffect
        delay(AppMotion.Standard.toLong())
        drilledGroup = null
    }
    // 宽屏无沉浸页、不接横滑，标题栏常驻
    LaunchedEffect(Unit) {
        onImmersivePageChange(false)
        onSwipeBackChange(false)
    }
    // 播放器点击歌手直达该歌手歌单
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
            )
        }
    }
}

// 左栏目的地：系统歌单与自定义歌单
internal sealed interface PlaylistDestination {
    data class Smart(val type: SmartPlaylistType) : PlaylistDestination
    data class Custom(val playlist: Playlist) : PlaylistDestination
}

// 右栏内容：下钻优先，其余按类目分发
@Composable
private fun PlaylistDetailPane(
    playlistStore: PlaylistStore,
    destination: PlaylistDestination,
    drilledGroup: PlaylistGroup?,
    playbackState: MusicPlaybackState,
    onOpenGroup: (PlaylistGroup) -> Unit,
) {
    if (drilledGroup != null) {
        Column(modifier = Modifier.fillMaxSize()) {
            WideDetailHeader(title = drilledGroup.name)
            if (drilledGroup.key.startsWith("album:")) {
                PlaylistAlbumSongsPage(group = drilledGroup, playbackState = playbackState)
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
                    WideDetailHeader(title = smartTypeLabel(destination.type))
                    PlaylistSmartTracksPage(type = destination.type, playbackState = playbackState)
                }
            SmartPlaylistType.ALBUM, SmartPlaylistType.ARTIST ->
                Column(modifier = Modifier.fillMaxSize()) {
                    WideDetailHeader(title = smartTypeLabel(destination.type))
                    PlaylistGroupsPage(
                        type = destination.type,
                        playbackState = playbackState,
                        onOpenGroup = onOpenGroup,
                    )
                }
        }
        is PlaylistDestination.Custom -> {
            // 可能刚改名，标题按当前存储取
            val current = playlistStore.playlists.find { it.id == destination.playlist.id } ?: destination.playlist
            Column(modifier = Modifier.fillMaxSize()) {
                WideDetailHeader(title = current.name)
                PlaylistTracksPage(playlist = current, playbackState = playbackState)
            }
        }
    }
}

// 右栏标题：只显示名字，返回交给系统键
@Composable
private fun WideDetailHeader(title: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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