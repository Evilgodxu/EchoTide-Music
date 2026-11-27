package com.yichao.evilgodxu.screens.home.component.playlist

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.data.music.playback.PlaylistSource
import com.yichao.evilgodxu.data.playlist.PlaylistGroup
import com.yichao.evilgodxu.data.playlist.albumGroups
import com.yichao.evilgodxu.data.playlist.applySavedTrackOrder
import com.yichao.evilgodxu.data.playlist.resolveTracks
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.LocalArtistOrderStore
import com.yichao.evilgodxu.ui.component.CoverPrefetch
import com.yichao.evilgodxu.ui.component.SongImmersiveBackground
import com.yichao.evilgodxu.ui.gesture.swipeBack
import com.yichao.evilgodxu.ui.icons.AppIcons
import kotlinx.coroutines.launch

// 两个视角：只换下方内容，头图与标签恒在
private enum class ArtistTab(@StringRes val labelRes: Int) {
    SONGS(R.string.playlist_tab_songs),
    ALBUMS(R.string.playlist_tab_albums),
}

/**
 * 歌手页：沉浸头图 + 歌曲/专辑标签，翻页由本页接管
 * 曲序＝曲库套存档，拖拽即写档；背景取色不回报
 * 标题栏恒隐藏，返回键与「歌曲」右滑退出
 */
@Composable
internal fun ArtistPlaylistPage(
    group: PlaylistGroup,
    playbackState: MusicPlaybackState,
    onOpenAlbum: (PlaylistGroup) -> Unit,
    // 歌曲为首标签，右滑转为回退一级
    onSwipeBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val artistOrderStore = LocalArtistOrderStore.current
    // 按分组 trackIds 解析，避免多歌手曲目被过滤
    val libraryById = remember(playbackState.libraryTracks) {
        playbackState.libraryTracks.associateBy { it.id }
    }
    val tracks = remember(playbackState.libraryTracks, group.key) {
        resolveTracks(playbackState.libraryTracks, group.trackIds)
    }
    // 顺序＝曲库顺序套存档，头图/背景/列表同源
    val savedOrder = artistOrderStore.orders[group.name].orEmpty()
    val orderedTracks = remember(tracks, savedOrder) { applySavedTrackOrder(tracks, savedOrder) }
    val unknownAlbum = stringResource(R.string.playlist_unknown_album)
    // 专辑按曲库顺序分组，封面不随歌曲排序变
    val albums = remember(tracks, unknownAlbum) { albumGroups(tracks, unknownAlbum) }
    // 标签分页由系统结算拖动与吸附
    val tabPagerState = rememberPagerState { ArtistTab.entries.size }
    // 歌曲标签归位时才接管右滑；专辑页右滑归分页
    val backSwipeEnabled = {
        tabPagerState.currentPage == ArtistTab.SONGS.ordinal && !tabPagerState.isScrollInProgress
    }
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            // 分页占横滑，本手势须在 Initial 判定
            .swipeBack(
                enabled = backSwipeEnabled,
                pass = PointerEventPass.Initial,
                onBack = onSwipeBack,
            ),
    ) {
        val headerHeight = (maxHeight * IMMERSIVE_HEADER_HEIGHT_RATIO).coerceAtMost(ImmersiveHeaderMaxHeight)
        // 整屏沉浸背景，与头图同源
        SongImmersiveBackground(track = orderedTracks.firstOrNull(), modifier = Modifier.fillMaxSize())
        Column(modifier = Modifier.fillMaxSize()) {
            ImmersiveGroupHeader(
                track = orderedTracks.firstOrNull(),
                title = group.name,
                subtitle = pluralStringResource(
                    R.plurals.music_panel_track_count,
                    orderedTracks.size,
                    orderedTracks.size,
                ),
                height = headerHeight,
            )
            ImmersiveTabBar(
                labels = ArtistTab.entries.map { stringResource(it.labelRes) },
                selectedIndex = tabPagerState.currentPage,
                onSelect = { tab -> scope.launch { tabPagerState.animateScrollToPage(tab) } },
            )
            HorizontalPager(
                state = tabPagerState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                // 两页常驻，保留排序与滚动位置
                beyondViewportPageCount = ArtistTab.entries.size,
                key = { ArtistTab.entries[it] },
            ) { index ->
                when (ArtistTab.entries[index]) {
                    ArtistTab.SONGS -> ArtistSongsContent(
                        group = group,
                        orderedTracks = orderedTracks,
                        playbackState = playbackState,
                    )
                    ArtistTab.ALBUMS -> ArtistAlbumList(
                        albums = albums,
                        libraryById = libraryById,
                        onOpenAlbum = onOpenAlbum,
                    )
                }
            }
        }
    }
}

// 专辑列表：封面取首曲，点击进专辑
@Composable
private fun ArtistAlbumList(
    albums: List<PlaylistGroup>,
    libraryById: Map<Long, MusicTrack>,
    onOpenAlbum: (PlaylistGroup) -> Unit,
) {
    if (albums.isEmpty()) {
        EmptyHint(text = stringResource(R.string.playlist_artist_no_albums))
        return
    }
    val listState = rememberLazyListState()
    // 封面预取，进入视口不闪占位符
    CoverPrefetch(listState) { index ->
        albums.getOrNull(index)?.trackIds?.firstOrNull()?.let { libraryById[it] }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(albums, key = { it.key }) { album ->
            PlaylistGroupRow(
                group = album,
                coverTrack = album.trackIds.firstOrNull()?.let { libraryById[it] },
                fallbackIcon = AppIcons.Album,
                onClick = { onOpenAlbum(album) },
            )
        }
    }
}

// 歌曲列表按存档次序，拖拽即写档；浸入页与宽屏共用
@Composable
internal fun ArtistSongsContent(
    group: PlaylistGroup,
    orderedTracks: List<MusicTrack>,
    playbackState: MusicPlaybackState,
) {
    val artistOrderStore = LocalArtistOrderStore.current
    val scope = rememberCoroutineScope()
    TracksContent(
        tracks = orderedTracks,
        source = PlaylistSource(group.key, group.name),
        playbackState = playbackState,
        trailingAction = {},
        onTrackLongClick = {},
        onReorder = { ordered ->
            scope.launch { artistOrderStore.setOrder(group.name, ordered.map { it.id }) }
        },
    )
}

// 宽屏歌手详情：仅歌曲列表，专辑由左栏承担
@Composable
internal fun PlaylistArtistTracksPage(
    group: PlaylistGroup,
    playbackState: MusicPlaybackState,
) {
    val artistOrderStore = LocalArtistOrderStore.current
    val tracks = remember(playbackState.libraryTracks, group.key) {
        resolveTracks(playbackState.libraryTracks, group.trackIds)
    }
    val savedOrder = artistOrderStore.orders[group.name].orEmpty()
    val orderedTracks = remember(tracks, savedOrder) { applySavedTrackOrder(tracks, savedOrder) }
    ArtistSongsContent(group = group, orderedTracks = orderedTracks, playbackState = playbackState)
}
