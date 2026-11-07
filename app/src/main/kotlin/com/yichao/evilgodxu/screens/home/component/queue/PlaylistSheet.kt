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
import com.yichao.evilgodxu.LocalMetadataEnricher
import com.yichao.evilgodxu.LocalPlaylistStore
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.data.music.playback.switchPlaylistSource
import com.yichao.evilgodxu.data.playlist.isViewSourceValid
import com.yichao.evilgodxu.data.playlist.resolveSourceTracks
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.permission.ensureTrackFileWritable
import com.yichao.evilgodxu.permission.rememberAllFilesAccessRequest
import com.yichao.evilgodxu.ui.component.RemoveTrackDialog
import com.yichao.evilgodxu.windowsize.rememberWindowLandscape
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// 播放列表面板：点击遮罩或关闭按钮收起
@Composable
internal fun PlaylistSheet(
    visible: Boolean,
    playbackState: MusicPlaybackState,
    onDismiss: () -> Unit,
    // 高级菜单的「查看频谱」：跳转频谱分析页，只传曲目标识
    onViewSpectrum: (Long) -> Unit = {},
    // 高级菜单的「编辑元数据」：跳转元数据编辑页，只传曲目标识
    onEditMetadata: (Long) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val requestAllFilesAccess = rememberAllFilesAccessRequest()
    val metadataEnricher = LocalMetadataEnricher.current
    // 竖屏播放列表高度减半，横屏全高显示
    val isPortrait = !rememberWindowLandscape()
    val sheetHeightFraction = if (isPortrait) 0.5f else 1f
    // 排序菜单高度上限：面板高度（窗口高度 × 展开比例）的 80%，超出部分在菜单内滚动，
    // 使朝下展开的菜单不会盖过整块面板；组件还会按锚点下方的可用空间再收紧，
    // 保证窗口较矮时菜单不翻转到排序按钮上方
    val sortMenuMaxHeight =
        LocalWindowInfo.current.containerDpSize.height * sheetHeightFraction * 0.8f
    // 歌单副标题点击后的快捷切换弹层
    var showSwitcher by remember { mutableStateOf(false) }
    // 面板展示的曲目：跟随播放队列时取播放队列，浏览态按来源歌单从全量库解析。
    // 浏览态只决定展示内容，与播放队列解耦，切换时不动播放器
    val playlistStore = LocalPlaylistStore.current
    // 自定义歌单需先完成读盘才能解析，否则浏览态会短暂展示空列表
    var playlistStoreLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        playlistStore.awaitLoaded(context)
        playlistStoreLoaded = true
    }
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
    ) {
        when {
            followsQueue -> playbackState.playlist
            viewedSource == null -> library
            else -> resolveSourceTracks(
                library,
                playlistStore.playlists,
                playbackState.likedIds,
                playbackState.recentPlayedIds,
                viewedSource,
            )
        }
    }
    val viewedKey = if (followsQueue) playbackState.queueFilter?.key else viewedSource?.key
    val viewedName = if (followsQueue) playbackState.queueFilter?.name else viewedSource?.name
    // 浏览的歌单已失效（被删除、专辑/艺术家分组消失）时回落到默认播放列表，避免面板停留在空列表；
    // 读盘完成前自定义歌单必然解析为空、扫描期间全量库可能暂时为空，都不作为失效依据
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
    // 左滑歌单归属目标：非空时显示归属弹窗，勾选/取消勾选即时加入或移出对应歌单
    var playlistTarget by remember { mutableStateOf<MusicTrack?>(null) }
    // 设为默认铃声的请求：非空时由安装组件提交并在授权往返后自动续做
    var soundRequest by remember { mutableStateOf<DefaultSoundRequest?>(null) }
    val shareChooserTitle = stringResource(R.string.playlist_advanced_menu_share)
    // 后台预读整份播放列表的歌词：曲目集合变化即触发，不等面板展开逐行懒加载，
    // 展开时歌词已就绪；幂等，已缓存/补全中/全量补全中的曲目自动跳过。
    // 封面不在此列：它按显示尺寸取图，进首页由 QueueCoverPreload 以当前曲目为中心辐射预取，
    // 滚动中由 CoverPrefetch 按可视区维持同规模窗口
    val playlistTrackIds = remember(tracks) { tracks.map { it.id } }
    LaunchedEffect(playlistTrackIds) {
        val currentId = playbackState.currentTrack?.id
        tracks
            .sortedBy { it.id != currentId }
            .forEach { playbackState.requestMetadata(it) }
    }
    // 曲目行封面：进首页即以当前曲目为中心向外辐射预取一批，同样不等面板展开
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
                    // 内边距只给左右与顶部：面板底色铺到屏幕底缘，底部再留内边距就会在末行下方
                    // 空出一条与底色同色、不随列表滚动的色带
                    .padding(start = 12.dp, end = 12.dp, top = 10.dp)
                    // 只避让键盘：键盘弹出时面板内容整体上移，其自身已覆盖导航栏区域，无需再叠加导航栏高度。
                    // 不避让导航栏——首页全沉浸已隐藏导航栏，在面板根节点预留这段高度同样只会留下底部色带
                    .imePadding(),
            ) {
                // 展开就绪：等面板滑入动画完成后再定位当前曲目，避免滚动与展开动画叠加卡顿
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
                            text = stringResource(R.string.home_player_empty),
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
                            // 彻底删除要动音频文件：无权限时就地申请，本次不弹确认框
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
            // 切换歌单即把所选来源设为默认队列的过滤条件：当前曲目在过滤结果中则按过滤顺序继续播放，
            // 不在则从过滤结果首曲起播。
            // 过滤已生效时面板回到跟随队列——展示的就是过滤结果，切回默认时排序按钮也随之恢复可用；
            // 来源为空（播放队列保持原样）才切到浏览态展示该来源
            onSwitch = { source ->
                val applied = switchPlaylistSource(
                    context = context,
                    state = playbackState,
                    source = source,
                    playlists = playlistStore.playlists,
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
                // 先收起面板再跳转，从频谱页返回时不会停在展开的面板上
                val target = advancedTrack
                advancedTrack = null
                if (target != null) {
                    onDismiss()
                    onViewSpectrum(target.id)
                }
            },
            onEditMetadata = {
                // 与频谱页同一处理：先收起面板再跳转，返回时不停在展开的面板上
                val target = advancedTrack
                advancedTrack = null
                if (target != null) {
                    onDismiss()
                    onEditMetadata(target.id)
                }
            },
            onDismiss = { advancedTrack = null },
        )
        // 安装不依赖对话框存活：面板收起后授权往返仍能续做
        DefaultSoundInstallerHost(
            request = soundRequest,
            onRequestChange = { soundRequest = it },
        )
    }
}

// 播放列表展开进入动画时长：等动画完成后才滚动定位当前曲目，避免动画叠加卡顿
private const val PLAYLIST_EXPAND_ANIM_MS = 300L

