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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.data.music.metadata.MusicMetadataWriter
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.data.music.playback.PlaylistSource
import com.yichao.evilgodxu.data.playlist.PlaylistGroup
import com.yichao.evilgodxu.data.playlist.applySavedTrackOrder
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.LocalAlbumOrderStore
import com.yichao.evilgodxu.permission.ensureTrackFileWritable
import com.yichao.evilgodxu.permission.rememberAllFilesAccessRequest
import com.yichao.evilgodxu.ui.component.DialogCard
import com.yichao.evilgodxu.ui.component.SongImmersiveBackground
import com.yichao.evilgodxu.utils.formatTime
import kotlinx.coroutines.launch

// 专辑页的两个视角：与歌手页同构，只切换下方内容，头图与标签栏恒在
private enum class AlbumTab(@StringRes val labelRes: Int) {
    SONGS(R.string.playlist_tab_songs),
    INFO(R.string.playlist_tab_info),
}

/**
 * 专辑页：与歌手页共用一套沉浸骨架——整屏同源背景 + 全宽头图（封面下缘渐隐 + 专辑名 + 歌手与曲目数）
 * + 标签栏（歌曲 / 信息）+ 左右滑动分页。
 *
 * 专辑以 albumId 认身份（分组 key 即 albumId），曲目按它实时筛出而不是照分组快照的 trackIds：
 * 长按改过专辑名的曲目会立刻离开本页，与分组列表的口径一致。头图封面、沉浸背景取色、
 * 信息页的总时长读的都是这一份曲目，不会各读各的成员。
 *
 * 歌曲顺序 = 曲库顺序套上该专辑的存档曲序（见 AlbumOrderStore）：分组由曲库即时解析，
 * 次序本身没有落脚点，故拖拽落定即写档，离开再进仍保持。头图封面与歌曲列表读的是同一份顺序。
 *
 * 标签由本页自己的分页承载，首页那层翻页在下钻层级已关闭（见 HomePanels）：一层滑动只对应一个去向。
 * 歌曲是首个标签，右滑在它上面没有可切换的目标，故转为回退一级（见 onSwipeBack）。
 *
 * 整页自带骨架而不套在面板的标题栏内容列里：头图与沉浸背景都要铺到状态栏后方。
 * 标题栏在该页始终隐藏（见 [PlaylistPanel] 的页面分派），页面因此不从顶部取任何基准，
 * 也不设页内返回入口——顶部整条让给头图，退出该页走系统返回键与「歌曲」标签上的右滑。
 *
 * 背景取色只用于本页画面，不向外回报（见 [ArtistPlaylistPage] 的同名说明）。
 */
@Composable
internal fun AlbumPlaylistPage(
    group: PlaylistGroup,
    playbackState: MusicPlaybackState,
    // 「歌曲」标签上继续右滑：该标签已是首个标签，右滑没有可切换的目标，转由面板回退一级
    onSwipeBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val albumOrderStore = LocalAlbumOrderStore.current
    val tracks = rememberAlbumTracks(group, playbackState)
    // 展示顺序 = 曲库顺序套上这张专辑的存档曲序。头图封面、沉浸背景取色与歌曲列表都读这一份，
    // 三者同源：拖拽落下后存档即更新，同一帧里换的是同一首歌的封面与底色
    val savedOrder = albumOrderStore.orders[group.key].orEmpty()
    val orderedTracks = remember(tracks, savedOrder) { applySavedTrackOrder(tracks, savedOrder) }
    // 标签分页：左右滑动即切换歌曲与信息，跟手拖动、吸附与回弹由系统结算
    val tabPagerState = rememberPagerState { AlbumTab.entries.size }
    // 右滑回退只在「歌曲」标签已归位时接管：该标签是首个标签，右滑在此没有分页去向；
    // 「信息」标签上的右滑归分页（切回歌曲），分页正在滑动或归位时也不该被本手势截走
    val backSwipeEnabled = {
        tabPagerState.currentPage == AlbumTab.SONGS.ordinal && !tabPagerState.isScrollInProgress
    }
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            // 分页占着横向滑动，本手势须在其之前判定（见 playlistBackSwipe 的 pass 参数）
            .playlistBackSwipe(
                enabled = backSwipeEnabled,
                pass = PointerEventPass.Initial,
                onBack = onSwipeBack,
            ),
    ) {
        val headerHeight = (maxHeight * IMMERSIVE_HEADER_HEIGHT_RATIO).coerceAtMost(ImmersiveHeaderMaxHeight)
        // 整屏沉浸背景：与头图同源，下缘渐隐的封面正是融入它
        SongImmersiveBackground(track = orderedTracks.firstOrNull(), modifier = Modifier.fillMaxSize())
        Column(modifier = Modifier.fillMaxSize()) {
            ImmersiveGroupHeader(
                track = orderedTracks.firstOrNull(),
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
                // 两页都常驻合成树：曲目列表持有滚动位置，来回切换不重建
                beyondViewportPageCount = AlbumTab.entries.size,
                key = { AlbumTab.entries[it] },
            ) { index ->
                when (AlbumTab.entries[index]) {
                    AlbumTab.SONGS -> AlbumSongsContent(
                        group = group,
                        orderedTracks = orderedTracks,
                        playbackState = playbackState,
                    )
                    AlbumTab.INFO -> AlbumInfoContent(tracks = orderedTracks)
                }
            }
        }
    }
}

// 专辑成员：分组 key 即 albumId，按它筛而非照分组快照的 trackIds——改过专辑名的曲目
// 会立刻离开本页，与分组列表对「这张专辑有哪些曲目」的判定保持一致
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

// 头图副标题：歌手 + 曲目数。专辑由 albumId 聚合而来，歌手名取首曲的口径，
// 多歌手合辑因此显示首个署名的歌手；取不到署名时只留曲目数，不留一条以分隔符开头的空行
@Composable
private fun albumHeaderSubtitle(tracks: List<MusicTrack>): String {
    val artist = tracks.firstOrNull()?.artist?.takeIf { it.isNotBlank() }
    return if (artist == null) {
        pluralStringResource(R.plurals.music_panel_track_count, tracks.size, tracks.size)
    } else {
        pluralStringResource(R.plurals.playlist_album_header_summary, tracks.size, artist, tracks.size)
    }
}

// 专辑曲目列表：长按歌曲进入专辑名改写，拖拽落定即写档；沉浸专辑页的「歌曲」标签与宽屏歌单面板的
// 专辑详情共用这一份实现，两处的长按口径与曲序因此一致
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
        // 确认后要把专辑名写回音频文件标签：无权限时就地申请，本次不弹编辑框
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

// 宽屏歌单面板的专辑详情：与沉浸专辑页的「歌曲」标签同源，仅呈现曲目列表。
// 宽屏的右栏只有一列内容，沉浸头图与标签分页在那里挤不下，专辑名由右栏标题承担
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

// 专辑信息：只陈列曲目页里读得到的概况，全部由曲目实时算出，不另存一份可能过期的描述
@Composable
private fun AlbumInfoContent(tracks: List<MusicTrack>) {
    if (tracks.isEmpty()) {
        EmptyHint(text = stringResource(R.string.playlist_empty))
        return
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        AlbumInfoRow(
            label = stringResource(R.string.playlist_album_info_tracks),
            value = pluralStringResource(R.plurals.music_panel_track_count, tracks.size, tracks.size),
        )
        AlbumInfoRow(
            label = stringResource(R.string.playlist_album_info_duration),
            value = formatTime(tracks.sumOf { it.duration }),
        )
    }
}

// 信息行：标签居左、取值居右并单行截断——取值过长时不该把行高撑成两行
@Composable
private fun AlbumInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            color = Color.White,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
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
