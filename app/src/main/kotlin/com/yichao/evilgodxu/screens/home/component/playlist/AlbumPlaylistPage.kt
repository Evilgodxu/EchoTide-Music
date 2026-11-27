package com.yichao.evilgodxu.screens.home.component.playlist

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.data.music.metadata.MusicMetadataWriter
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.data.music.playback.PlaylistSource
import com.yichao.evilgodxu.data.playlist.PlaylistGroup
import com.yichao.evilgodxu.data.playlist.applySavedTrackOrder
import com.yichao.evilgodxu.screens.home.component.playlist.info.AlbumInfoContent
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.LocalAlbumOrderStore
import com.yichao.evilgodxu.permission.ensureTrackFileWritable
import com.yichao.evilgodxu.permission.rememberAllFilesAccessRequest
import com.yichao.evilgodxu.ui.component.DialogCard
import com.yichao.evilgodxu.ui.component.SongImmersiveBackground
import com.yichao.evilgodxu.ui.gesture.swipeBack
import kotlinx.coroutines.launch

// 两个视角：只换下方内容，头图与标签恒在
private enum class AlbumTab(@StringRes val labelRes: Int) {
    SONGS(R.string.playlist_tab_songs),
    INFO(R.string.playlist_tab_info),
}

/**
 * 专辑页：与歌手页共用骨架，同源背景、头图与标签
 * 以 albumId 认身份；曲序＝曲库套存档，拖拽即写档
 * 标题栏恒隐藏，返回键与「歌曲」右滑退出
 */
@Composable
internal fun AlbumPlaylistPage(
    group: PlaylistGroup,
    playbackState: MusicPlaybackState,
    // 歌曲为首标签，右滑转为回退一级
    onSwipeBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val albumOrderStore = LocalAlbumOrderStore.current
    val tracks = rememberAlbumTracks(group, playbackState)
    // 顺序＝曲库顺序套存档，头图/背景/列表同源
    val savedOrder = albumOrderStore.orders[group.key].orEmpty()
    val orderedTracks = remember(tracks, savedOrder) { applySavedTrackOrder(tracks, savedOrder) }
    // 标签分页由系统结算拖动与吸附
    val tabPagerState = rememberPagerState { AlbumTab.entries.size }
    // 歌曲标签归位时才接管右滑；信息页右滑归分页
    val backSwipeEnabled = {
        tabPagerState.currentPage == AlbumTab.SONGS.ordinal && !tabPagerState.isScrollInProgress
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
        // 头图取正在播放那首，否则首曲
        val playingId = playbackState.currentTrack
            ?.takeIf { current -> orderedTracks.any { it.id == current.id } }
            ?.id
        val displayTrack = orderedTracks.firstOrNull { it.id == playingId } ?: orderedTracks.firstOrNull()
        // 整屏沉浸背景，与头图同源
        SongImmersiveBackground(track = displayTrack, modifier = Modifier.fillMaxSize())
        Column(modifier = Modifier.fillMaxSize()) {
            ImmersiveGroupHeader(
                track = displayTrack,
                title = group.name,
                subtitle = albumHeaderSubtitle(orderedTracks),
                height = headerHeight,
            )
            ImmersiveTabBar(
                labels = AlbumTab.entries.map { stringResource(it.labelRes) },
                selectedIndex = tabPagerState.currentPage,
                onSelect = { tab -> scope.launch { tabPagerState.animateScrollToPage(tab) } },
            )
            HorizontalPager(
                state = tabPagerState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                // 两页常驻，保留滚动位置
                beyondViewportPageCount = AlbumTab.entries.size,
                key = { AlbumTab.entries[it] },
            ) { index ->
                when (AlbumTab.entries[index]) {
                    AlbumTab.SONGS -> AlbumSongsContent(
                        group = group,
                        orderedTracks = orderedTracks,
                        playbackState = playbackState,
                    )
                    AlbumTab.INFO -> AlbumInfoContent(
                        track = displayTrack,
                        playbackState = playbackState,
                        visible = tabPagerState.currentPage == AlbumTab.INFO.ordinal,
                    )
                }
            }
        }
    }
}

// 按 albumId 实时筛曲目，改名即离开本页
@Composable
private fun rememberAlbumTracks(
    group: PlaylistGroup,
    playbackState: MusicPlaybackState,
): List<MusicTrack> {
    val library = playbackState.libraryTracks
    return remember(library, group.key) {
        val albumId = group.key.removePrefix("album:").toLongOrNull()
        if (albumId == null) emptyList() else library.filter { it.albumId == albumId }
    }
}

// 副标题＝首曲歌手 + 曲目数；无署名只留数量
@Composable
private fun albumHeaderSubtitle(tracks: List<MusicTrack>): String {
    val artist = tracks.firstOrNull()?.artist?.takeIf { it.isNotBlank() }
    return if (artist == null) {
        pluralStringResource(R.plurals.music_panel_track_count, tracks.size, tracks.size)
    } else {
        pluralStringResource(R.plurals.playlist_album_header_summary, tracks.size, artist, tracks.size)
    }
}

// 曲目列表：长按改专辑名，拖拽写档；两处共用
@Composable
internal fun AlbumSongsContent(
    group: PlaylistGroup,
    orderedTracks: List<MusicTrack>,
    playbackState: MusicPlaybackState,
) {
    val albumOrderStore = LocalAlbumOrderStore.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val requestAllFilesAccess = rememberAllFilesAccessRequest()
    // 长按歌曲：编辑该歌曲的专辑元数据
    var editAlbumTrack by remember { mutableStateOf<MusicTrack?>(null) }
    TracksContent(
        tracks = orderedTracks,
        source = PlaylistSource(group.key, group.name),
        playbackState = playbackState,
        trailingAction = {},
        // 写文件需权限，无权限先申请再弹框
        onTrackLongClick = { track ->
            if (ensureTrackFileWritable(requestAllFilesAccess)) editAlbumTrack = track
        },
        onReorder = { ordered ->
            scope.launch { albumOrderStore.setOrder(group.key, ordered.map { it.id }) }
        },
    )
    EditAlbumDialog(
        track = editAlbumTrack,
        onConfirm = { track, albumName ->
            playbackState.updateTrack(track.copy(albumName = albumName))
            editAlbumTrack = null
            // 将专辑名写回音频文件标签
            scope.launch { MusicMetadataWriter.writeAlbum(context, track, albumName) }
        },
        onDismiss = { editAlbumTrack = null },
    )
}

// 宽屏专辑详情：仅曲目列表；头图与标签放不下，标题由右栏承担
@Composable
internal fun PlaylistAlbumSongsPage(
    group: PlaylistGroup,
    playbackState: MusicPlaybackState,
) {
    val albumOrderStore = LocalAlbumOrderStore.current
    val tracks = rememberAlbumTracks(group, playbackState)
    val savedOrder = albumOrderStore.orders[group.key].orEmpty()
    val orderedTracks = remember(tracks, savedOrder) { applySavedTrackOrder(tracks, savedOrder) }
    AlbumSongsContent(group = group, orderedTracks = orderedTracks, playbackState = playbackState)
}

// 专辑视图长按歌曲：编辑该歌曲的专辑元数据
@Composable
private fun EditAlbumDialog(
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
