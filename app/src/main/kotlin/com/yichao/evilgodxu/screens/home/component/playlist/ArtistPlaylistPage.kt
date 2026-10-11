package com.yichao.evilgodxu.screens.home.component.playlist

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.data.music.playback.PlaylistSource
import com.yichao.evilgodxu.data.music.playback.TrackSwitchKind
import com.yichao.evilgodxu.data.playlist.PlaylistGroup
import com.yichao.evilgodxu.data.playlist.albumGroups
import com.yichao.evilgodxu.data.playlist.resolveTracks
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.screens.home.component.player.HomeImmersiveCover
import com.yichao.evilgodxu.ui.component.CoverPrefetch
import com.yichao.evilgodxu.ui.component.SongImmersiveBackground
import com.yichao.evilgodxu.ui.icons.AppIcons
import kotlinx.coroutines.launch

// 头图高度占本页高度的比例与其上限：构图取播放页沉浸封面的「上重下轻」，尺寸随窗口计算，
// 各高宽比下都与下方列表分得住；上限挡住超长窗口（大屏、折叠展开）把列表挤成一条
private const val ARTIST_HEADER_HEIGHT_RATIO = 0.4f
private val ArtistHeaderMaxHeight = 320.dp

// 歌手页的两个视角：只切换下方内容，头图与标签栏恒在
private enum class ArtistTab(@StringRes val labelRes: Int) {
    SONGS(R.string.playlist_tab_songs),
    ALBUMS(R.string.playlist_tab_albums),
}

/**
 * 歌手页：上方为沉浸头图（歌手首曲的专辑封面，沿播放页封面的沉浸处理），
 * 下方以标签页区分歌曲与专辑。
 *
 * 标签由本页自己的分页承载：左右滑动在歌曲与专辑之间切换，首页那层翻页在该页关闭
 * （见 [HomePanels]）——一层滑动只对应一个去向，滑动落在哪个列表上都不该同时换标签又换页。
 *
 * 整页自带骨架而非套在面板的标题栏内容列里：沉浸头图与沉浸背景都要铺到状态栏后方，
 * 与播放页的同名处理一致（见 [PlaylistPanel] 的页面分派）。标题栏在该页始终隐藏
 * （首页组装器按 HomePanelState.playlistImmersive 收起），页面因此不从顶部取任何基准，
 * 也不设页内返回入口——顶部整条让给头图，退出该页走系统返回键（面板的 BackHandler
 * 逐级回退）；横向滑动已被标签分页占用，不再兼作收回面板。
 *
 * 沉浸背景的流动帧随本页在合成树上存亡（面板离开当前页即把回退栈复位到总览页，
 * 见 [PlaylistPanel]），故不必再按可见性门控：滑入动画期间它已随本页一同铺开，
 * 中途才补上反而会让整屏底色跳一次。
 *
 * 背景取色只用于本页画面，不向外回报：取色持久化与菜单底色都锚定当前播放曲目
 * （见 MusicPlaybackState.saveBackgroundColor 与 HomeShell 的回调），
 * 此处取的是歌手首曲，回报会把这位歌手的底色写到正在播放的那一首名下。
 */
@Composable
internal fun ArtistPlaylistPage(
    group: PlaylistGroup,
    playbackState: MusicPlaybackState,
    onOpenAlbum: (PlaylistGroup) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    // 与分组列表同一口径：按分组保存的 trackIds 解析，避免多歌手曲目（如 "A / B"）
    // 因 artist 整串不等于歌手名而被过滤，导致详情曲目少于列表展示数
    val libraryById = remember(playbackState.libraryTracks) {
        playbackState.libraryTracks.associateBy { it.id }
    }
    val tracks = remember(playbackState.libraryTracks, group.key) {
        resolveTracks(playbackState.libraryTracks, group.trackIds)
    }
    val unknownAlbum = stringResource(R.string.playlist_unknown_album)
    // 专辑分组是该歌手全量曲目的扫描 + 排序，缓存到曲目或本地化文案变化为止
    val albums = remember(tracks, unknownAlbum) { albumGroups(tracks, unknownAlbum) }
    // 标签分页：左右滑动即切换歌曲与专辑（首页那层翻页在该页已关闭，见 HomePanels），
    // 跟手拖动、吸附与回弹由系统结算，本页不自行判定滑动
    val tabPagerState = rememberPagerState { ArtistTab.entries.size }
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val headerHeight = (maxHeight * ARTIST_HEADER_HEIGHT_RATIO).coerceAtMost(ArtistHeaderMaxHeight)
        // 整屏沉浸背景：与头图同源，下缘渐隐的封面正是融入它
        SongImmersiveBackground(track = tracks.firstOrNull(), modifier = Modifier.fillMaxSize())
        Column(modifier = Modifier.fillMaxSize()) {
            ArtistHeader(
                track = tracks.firstOrNull(),
                name = group.name,
                trackCount = tracks.size,
                height = headerHeight,
            )
            ArtistTabBar(
                selected = ArtistTab.entries[tabPagerState.currentPage],
                onSelect = { tab -> scope.launch { tabPagerState.animateScrollToPage(tab.ordinal) } },
            )
            HorizontalPager(
                state = tabPagerState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                // 两页都常驻合成树：曲目列表持有拖拽排序与滚动位置，来回切换不重建
                beyondViewportPageCount = ArtistTab.entries.size,
                key = { ArtistTab.entries[it] },
            ) { index ->
                when (ArtistTab.entries[index]) {
                    ArtistTab.SONGS -> TracksContent(
                        tracks = tracks,
                        source = PlaylistSource(group.key, group.name),
                        playbackState = playbackState,
                        scope = scope,
                        trailingAction = {},
                        onTrackLongClick = {},
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

// 沉浸头图：封面全宽置顶、下缘渐隐融入同源背景（与播放页同一处理），上端铺到状态栏后方；
// 歌手名与曲目数叠在头图下缘，页内不含任何按钮，整幅头图保持干净
@Composable
private fun ArtistHeader(
    track: MusicTrack?,
    name: String,
    trackCount: Int,
    height: Dp,
) {
    Box(modifier = Modifier.fillMaxWidth().height(height)) {
        HomeImmersiveCover(
            track = track,
            // 换歌手是整页跳转，不是沿播放队列的前后位移，换图没有可读的方向；
            // 同一位歌手内曲目变化只影响背景取色，头图本身按标识判定同图而不会重播过渡
            kind = TrackSwitchKind.Select,
            modifier = Modifier.fillMaxSize(),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
        ) {
            Text(
                text = name,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = pluralStringResource(R.plurals.music_panel_track_count, trackCount, trackCount),
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 12.sp,
            )
        }
    }
}

// 标签栏：选中项以高亮文字与下划线标示。下划线取标签文字宽度，故标签须以最大固有宽度定宽——
// 直接让下划线 fillMaxWidth 会撑满整行（列宽本由最宽子项决定，而它就是那条下划线）
@Composable
private fun ArtistTabBar(
    selected: ArtistTab,
    onSelect: (ArtistTab) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        ArtistTab.entries.forEach { tab ->
            val isSelected = tab == selected
            Column(
                modifier = Modifier
                    .width(IntrinsicSize.Max)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onSelect(tab) }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    // 字重两种状态一致：选中态改字重会让标签宽度随之变化，下划线跟着一收一放
                    text = stringResource(tab.labelRes),
                    color = if (isSelected) Color.White else Color.White.copy(alpha = 0.6f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(
                            color = if (isSelected) Color.White else Color.Transparent,
                            shape = RoundedCornerShape(1.dp),
                        ),
                )
            }
        }
    }
}

// 歌手专辑列表：封面取专辑内该歌手的曲目首曲，点击进入该专辑的曲目页
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
    // 专辑行封面取专辑首曲：可视区邻域提前取图，滚动进入视口时不再先闪占位符
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
