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
import com.yichao.evilgodxu.screens.home.component.playlist.PlaylistAlbumTracksPage
import com.yichao.evilgodxu.screens.home.component.playlist.PlaylistArtistTracksPage
import com.yichao.evilgodxu.screens.home.component.playlist.PlaylistGroupsPage
import com.yichao.evilgodxu.screens.home.component.playlist.PlaylistSmartTracksPage
import com.yichao.evilgodxu.screens.home.component.playlist.PlaylistTracksPage
import com.yichao.evilgodxu.ui.component.smartTypeLabel

// 左栏宽度：平板横屏下既容得下歌单名与计数，又不挤占右栏内容
private val PlaylistRailWidth = 300.dp

// 宽屏歌单面板：左栏常驻导航 + 右栏所选类目的内容。横向宽度在此即导航，点选直接换右栏，
// 不设整屏沉浸页；仅专辑/歌手分组列表的下钻在右栏内保留一层回退
@Composable
internal fun PlaylistWidePanel(
    // 面板是否停在歌单页：返回键的下钻回退只在本页在场时接管，否则会抢走其它页面自己的返回处理
    visible: Boolean,
    playbackState: MusicPlaybackState,
    playlistStore: PlaylistStore,
    menuBackgroundColor: Color,
    // 标题栏高度：本面板的内容都从标题栏下方开始
    topInset: Dp,
    onImmersivePageChange: (Boolean) -> Unit,
    // 本面板不接管横向滑动：随形态切换复位，避免从窄屏下钻页旋转过来时首页翻页仍被关闭
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
    // 左栏当前选中项，右栏内容随之切换；切项即清空右栏内的下钻
    var destination by remember {
        mutableStateOf<PlaylistDestination>(PlaylistDestination.Smart(SmartPlaylistType.RECENT))
    }
    // 右栏内的一层下钻：从专辑/歌手分组列表进入该分组的曲目
    var drilledGroup by remember { mutableStateOf<PlaylistGroup?>(null) }
    // 下钻进入分组曲目后，系统返回键先退回分组列表，再退才由首页收起面板
    BackHandler(enabled = visible && drilledGroup != null) { drilledGroup = null }
    // 离开歌单页即退出下钻：与窄屏面板「离开即复位页面栈」同口径，回来落在类目列表而非上次的分组里
    LaunchedEffect(visible) { if (!visible) drilledGroup = null }
    // 宽屏面板无整屏沉浸页、也不接管横向滑动：标题栏始终在场，首页翻页照常
    LaunchedEffect(Unit) {
        onImmersivePageChange(false)
        onSwipeBackChange(false)
    }
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
) {
    if (drilledGroup != null) {
        Column(modifier = Modifier.fillMaxSize()) {
            WideDetailHeader(title = drilledGroup.name)
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
            // 歌单可能刚被重命名：标题与内容都按当前存储里的那份取名
            val current = playlistStore.playlists.find { it.id == destination.playlist.id } ?: destination.playlist
            Column(modifier = Modifier.fillMaxSize()) {
                WideDetailHeader(title = current.name)
                PlaylistTracksPage(playlist = current, playbackState = playbackState)
            }
        }
    }
}

// 右栏标题栏：仅承载当前内容的名字。下钻的返回交给系统返回键（见 [PlaylistWidePanel] 的 BackHandler），
// 页内不再设返回入口
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