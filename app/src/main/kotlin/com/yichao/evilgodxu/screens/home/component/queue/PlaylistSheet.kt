package com.yichao.evilgodxu.screens.home.component.queue

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.data.music.clip.RingtoneUsage
import com.yichao.evilgodxu.data.music.clip.shareTrack
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.LocalAlbumOrderStore
import com.yichao.evilgodxu.LocalArtistOrderStore
import com.yichao.evilgodxu.LocalFavoriteOrderStore
import com.yichao.evilgodxu.LocalMetadataEnricher
import com.yichao.evilgodxu.LocalPlaylistStore
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.data.music.playback.switchPlaylistSource
import com.yichao.evilgodxu.data.playlist.isViewSourceValid
import com.yichao.evilgodxu.data.playlist.resolveSourceTracks
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.permission.ensureTrackFileWritable
import com.yichao.evilgodxu.permission.rememberAllFilesAccessRequest
import com.yichao.evilgodxu.screens.home.component.player.libraryEmptyHint
import com.yichao.evilgodxu.screens.home.component.player.textRes
import com.yichao.evilgodxu.ui.component.RemoveTrackDialog
import com.yichao.evilgodxu.windowsize.rememberWindowLandscape
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// 播放列表面板：点击遮罩或关闭按钮收起
@Composable
internal fun PlaylistSheet(
    visible: Boolean,
    playbackState: MusicPlaybackState,
    // 授权状态，用于区分空列表成因
    mediaAudioGranted: Boolean,
    onDismiss: () -> Unit,
    // 「查看频谱」：跳转频谱页
    onViewSpectrum: (Long) -> Unit = {},
    // 「编辑元数据」：跳转元数据页
    onEditMetadata: (Long) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val requestAllFilesAccess = rememberAllFilesAccessRequest()
    val metadataEnricher = LocalMetadataEnricher.current
    // 竖屏播放列表高度减半，横屏全高显示
    val isPortrait = !rememberWindowLandscape()
    val sheetHeightFraction = if (isPortrait) 0.5f else 1f
    // 菜单高上限＝面板高 × 80%，超出在菜单内滚动。
    // 组件还会按锚点下方空间再收紧，避免翻转。
    val sortMenuMaxHeight =
        LocalWindowInfo.current.containerDpSize.height * sheetHeightFraction * 0.8f
    // 歌单副标题点击后的快捷切换弹层
    var showSwitcher by remember { mutableStateOf(false) }
    // 曲目：跟随队列时取队列，浏览态按来源解析，不动播放器
    val playlistStore = LocalPlaylistStore.current
    val albumOrders = LocalAlbumOrderStore.current.orders
    val artistOrders = LocalArtistOrderStore.current.orders
    val favoriteOrder = LocalFavoriteOrderStore.current.order
    // 读盘前按空处理，避免与「暂无」混淆
    val playlistStoreLoaded = playlistStore.isLoaded
    val followsQueue = playbackState.viewedFollowsQueue
    val viewedSource = playbackState.viewedSource
    val library = playbackState.libraryTracks
    val tracks = remember(
        followsQueue,
        viewedSource,
        library,
        playlistStore.playlists,
        playbackState.playlist,
        playbackState.likedIds,
        playbackState.recentPlayedIds,
        albumOrders,
        artistOrders,
        favoriteOrder,
    ) {
        when {
            followsQueue -> playbackState.playlist
            viewedSource == null -> library
            else -> resolveSourceTracks(
                all = library,
                playlists = playlistStore.playlists,
                likedIds = playbackState.likedIds,
                recentPlayedIds = playbackState.recentPlayedIds,
                source = viewedSource,
                albumOrders = albumOrders,
                artistOrders = artistOrders,
                favoriteOrder = favoriteOrder,
            )
        }
    }
    val viewedKey = if (followsQueue) playbackState.queueFilter?.key else viewedSource?.key
    val viewedName = if (followsQueue) playbackState.queueFilter?.name else viewedSource?.name
    // 歌单失效时回落默认；读盘与扫描期间不算失效
    LaunchedEffect(playlistStoreLoaded, playbackState.isScanning, followsQueue, viewedSource, tracks) {
        if (playlistStoreLoaded && !playbackState.isScanning && !followsQueue && viewedSource != null &&
            !isViewSourceValid(playlistStore.playlists, tracks, viewedSource)
        ) {
            playbackState.viewPlaylist(null)
        }
    }
    // 长按删除目标：非空时显示确认弹窗
    var deleteTrack by remember { mutableStateOf<MusicTrack?>(null) }
    // 右滑高级菜单目标：非空时显示菜单对话框
    var advancedTrack by remember { mutableStateOf<MusicTrack?>(null) }
    // 归属目标：非空时归属弹窗，勾选即时增删
    var playlistTarget by remember { mutableStateOf<MusicTrack?>(null) }
    // 铃声请求，授权往返后自动续做
    var soundRequest by remember { mutableStateOf<DefaultSoundRequest?>(null) }
    val shareChooserTitle = stringResource(R.string.playlist_advanced_menu_share)
    // 预读整份列表歌词，曲目变化即触发，已缓存跳过
    // 封面按显示尺寸另行取图
    val playlistTrackIds = remember(tracks) { tracks.map { it.id } }
    LaunchedEffect(playlistTrackIds) {
        val currentId = playbackState.currentTrack?.id
        tracks
            .sortedBy { it.id != currentId }
            .forEach { playbackState.requestMetadata(it) }
    }
    // 进首页即预取歌曲封面，不等面板展开
    QueueCoverPreload(
        tracks = tracks,
        currentTrackId = playbackState.currentTrack?.id,
    )
    Box(Modifier.fillMaxSize()) {
        // 遮罩，点击收起
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
        }
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(animationSpec = tween(300)) { it } + fadeIn(),
            exit = slideOutVertically(animationSpec = tween(300)) { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(sheetHeightFraction)
                    .background(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                    )
                    // 内边距只给左右与顶部，底部留白会露出色带
                    .padding(start = 12.dp, end = 12.dp, top = 10.dp)
                    // 只避让键盘；导航栏已隐藏，预留高度只会留色带
                    .imePadding(),
            ) {
                // 等滑入动画完成再定位当前曲目，避免卡顿
                var playlistSettled by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    playlistSettled = false
                    delay(PLAYLIST_EXPAND_ANIM_MS)
                    playlistSettled = true
                }
                PlaylistSheetHeader(
                    playbackState = playbackState,
                    trackCount = tracks.size,
                    viewedName = viewedName,
                    followsQueue = followsQueue,
                    sortMenuMaxHeight = sortMenuMaxHeight,
                    onOpenSwitcher = { showSwitcher = true },
                    onDismiss = onDismiss,
                )
                Spacer(Modifier.height(4.dp))
                if (playbackState.isScanning) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                } else if (tracks.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(
                                libraryEmptyHint(mediaAudioGranted, playbackState.isScanning).textRes
                            ),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    PlaylistSheetList(
                        playbackState = playbackState,
                        tracks = tracks,
                        followsQueue = followsQueue,
                        viewedSource = viewedSource,
                        playlistSettled = playlistSettled,
                        playlistTrackIds = playlistTrackIds,
                        onDismiss = onDismiss,
                        onLongPressTrack = { track ->
                            // 删文件需权限，无权限先申请再弹框
                            if (ensureTrackFileWritable(requestAllFilesAccess)) deleteTrack = track
                        },
                        onSwipePlaylist = { playlistTarget = it },
                        onSwipeAdvanced = { advancedTrack = it },
                    )
                }
            }
        }
        PlaylistSwitcher(
            visible = showSwitcher,
            playbackState = playbackState,
            currentKey = viewedKey,
            // 选来源即设为队列过滤：当前曲目在结果中则续播，否则从首曲起。
            // 过滤生效时面板回跟随队列；来源为空才进浏览态。
            onSwitch = { source ->
                val applied = switchPlaylistSource(
                    context = context,
                    state = playbackState,
                    source = source,
                    playlists = playlistStore.playlists,
                    albumOrders = albumOrders,
                    artistOrders = artistOrders,
                    favoriteOrder = favoriteOrder,
                    metadataEnricher = metadataEnricher,
                )
                if (applied) playbackState.followPlaybackQueue() else playbackState.viewPlaylist(source)
            },
            onDismiss = { showSwitcher = false },
        )
        RemoveTrackDialog(
            track = deleteTrack,
            titleRes = R.string.music_panel_delete_title,
            messageRes = R.string.music_panel_delete_message,
            confirmRes = R.string.music_panel_delete_confirm,
            onConfirm = { track ->
                scope.launch { playbackState.deleteSongPermanently(context, track) }
                deleteTrack = null
            },
            onDismiss = { deleteTrack = null },
        )
        TrackPlaylistDialog(
            track = playlistTarget,
            libraryTracks = library,
            onDismiss = { playlistTarget = null },
        )
        TrackAdvancedMenuDialog(
            visible = advancedTrack != null,
            actionsEnabled = advancedTrack?.isLocalAudioSource == true,
            onShare = {
                advancedTrack?.let { shareTrack(context, it, shareChooserTitle) }
                advancedTrack = null
            },
            onSetRingtone = {
                advancedTrack?.let { soundRequest = DefaultSoundRequest(it, RingtoneUsage.RINGTONE) }
                advancedTrack = null
            },
            onSetAlarm = {
                advancedTrack?.let { soundRequest = DefaultSoundRequest(it, RingtoneUsage.ALARM) }
                advancedTrack = null
            },
            onViewSpectrum = {
                // 先收起面板再跳转，返回不停在展开面板
                val target = advancedTrack
                advancedTrack = null
                if (target != null) {
                    onDismiss()
                    onViewSpectrum(target.id)
                }
            },
            onEditMetadata = {
                // 先收起再跳转，返回不停在展开面板
                val target = advancedTrack
                advancedTrack = null
                if (target != null) {
                    onDismiss()
                    onEditMetadata(target.id)
                }
            },
            onDismiss = { advancedTrack = null },
        )
        // 安装不依赖对话框，面板收起仍续做
        DefaultSoundInstallerHost(
            request = soundRequest,
            onRequestChange = { soundRequest = it },
        )
    }
}

// 展开动画 300ms，完成后才滚动定位
private const val PLAYLIST_EXPAND_ANIM_MS = 300L

