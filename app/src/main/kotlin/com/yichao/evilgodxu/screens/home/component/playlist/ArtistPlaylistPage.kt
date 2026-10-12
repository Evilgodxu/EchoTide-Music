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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalContext
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
import com.yichao.evilgodxu.data.playlist.applySavedTrackOrder
import com.yichao.evilgodxu.data.playlist.resolveTracks
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.LocalArtistOrderStore
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
 * （见 HomePanels 的翻页开关）——一层滑动只对应一个去向，滑动落在哪个列表上都不该同时换标签又换页。
 * 歌曲是首个标签，右滑在它上面没有可切换的目标，故转为回退一级（见 onSwipeBack）：去向仍然唯一。
 *
 * 歌曲顺序 = 曲库顺序套上该歌手的存档曲序（见 ArtistOrderStore）：分组由曲库即时解析，
 * 次序本身没有落脚点，故拖拽落定即写档，离开再进仍保持。头图封面、沉浸背景取色与歌曲列表
 * 读的是同一份顺序——拖到哪首就显示哪首，三者不会各读各的先后；专辑列表另按曲库顺序扫，
 * 专辑封面不该因歌曲次序调整而改换（见下方 albums）。
 *
 * 整页自带骨架而非套在面板的标题栏内容列里：沉浸头图与沉浸背景都要铺到状态栏后方，
 * 与播放页的同名处理一致（见 [PlaylistPanel] 的页面分派）。标题栏在该页始终隐藏
 * （首页组装器按 HomePanelState.playlistImmersive 收起），页面因此不从顶部取任何基准，
 * 也不设页内返回入口——顶部整条让给头图，退出该页走系统返回键（面板的 BackHandler 逐级回退）
 * 与「歌曲」标签上的右滑。
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
    // 「歌曲」标签上继续右滑：该标签已是首个标签，右滑没有可切换的目标，转由面板回退一级
    onSwipeBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val artistOrderStore = LocalArtistOrderStore.current
    // 与分组列表同一口径：按分组保存的 trackIds 解析，避免多歌手曲目（如 "A / B"）
    // 因 artist 整串不等于歌手名而被过滤，导致详情曲目少于列表展示数
    val libraryById = remember(playbackState.libraryTracks) {
        playbackState.libraryTracks.associateBy { it.id }
    }
    val tracks = remember(playbackState.libraryTracks, group.key) {
        resolveTracks(playbackState.libraryTracks, group.trackIds)
    }
    // 展示顺序 = 曲库顺序套上该歌手的存档曲序。头图封面、沉浸背景取色与歌曲列表都读这一份，
    // 三者同源：拖拽落下后存档即更新，同一帧里换的是同一首歌的封面与底色，不会各读各的先后
    val savedOrder = artistOrderStore.orders[group.name].orEmpty()
    val orderedTracks = remember(tracks, savedOrder) { applySavedTrackOrder(tracks, savedOrder) }
    val unknownAlbum = stringResource(R.string.playlist_unknown_album)
    // 专辑分组按曲库顺序扫（不套存档）：专辑封面取该专辑在曲库里的首曲，
    // 不该因为用户调整了歌曲次序而改换；顺带让这份分组缓存不在拖拽过程中逐次重算
    val albums = remember(tracks, unknownAlbum) { albumGroups(tracks, unknownAlbum) }
    // 标签分页：左右滑动即切换歌曲与专辑（首页那层翻页在该页已关闭，见 HomePanels），
    // 标签切换的跟手拖动、吸附与回弹由系统结算，本页不自行判定
    val tabPagerState = rememberPagerState { ArtistTab.entries.size }
    // 右滑回退只在「歌曲」标签已归位时接管：该标签是首个标签，右滑在此没有分页去向；
    // 「专辑」标签上的右滑归分页（切回歌曲），分页正在滑动或归位时也不该被本手势截走
    val backSwipeEnabled = {
        tabPagerState.currentPage == ArtistTab.SONGS.ordinal && !tabPagerState.isScrollInProgress
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
        val headerHeight = (maxHeight * ARTIST_HEADER_HEIGHT_RATIO).coerceAtMost(ArtistHeaderMaxHeight)
        // 整屏沉浸背景：与头图同源，下缘渐隐的封面正是融入它
        SongImmersiveBackground(track = orderedTracks.firstOrNull(), modifier = Modifier.fillMaxSize())
        Column(modifier = Modifier.fillMaxSize()) {
            ArtistHeader(
                track = orderedTracks.firstOrNull(),
                name = group.name,
                trackCount = orderedTracks.size,
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

// 歌手歌曲列表：曲序按该歌手的存档次序铺开——分组由曲库即时解析，次序本身没有落脚点，
// 故拖拽落定即写档。沉浸歌手页的「歌曲」标签与宽屏歌单面板的歌手详情共用这一份实现，
// 两处的曲序与拖拽口径因此一致
@Composable
internal fun ArtistSongsContent(
    group: PlaylistGroup,
    orderedTracks: List<MusicTrack>,
    playbackState: MusicPlaybackState,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val artistOrderStore = LocalArtistOrderStore.current
    TracksContent(
        tracks = orderedTracks,
        source = PlaylistSource(group.key, group.name),
        playbackState = playbackState,
        scope = scope,
        trailingAction = {},
        onTrackLongClick = {},
        onReorder = { ordered -> artistOrderStore.setOrder(context, group.name, ordered.map { it.id }) },
    )
}

// 宽屏歌单面板的歌手详情：与沉浸歌手页的「歌曲」标签同源，仅呈现该歌手的歌曲列表。
// 专辑浏览在宽屏由左栏的专辑类目承担，故此处不重复提供专辑标签
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
