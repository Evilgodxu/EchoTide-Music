package com.yichao.evilgodxu.screens.home.compact.player

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.data.music.metadata.MusicMetadataWriter
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.data.music.playback.parseTrackArtists
import com.yichao.evilgodxu.permission.hasAllFilesAccess
import com.yichao.evilgodxu.screens.home.component.dialog.ArtistPickerDialog
import com.yichao.evilgodxu.screens.home.component.player.MarqueeInfoLine
import com.yichao.evilgodxu.ui.component.player.MiniContextMenu
import com.yichao.evilgodxu.ui.component.TrackSwitchTransition
import com.yichao.evilgodxu.ui.component.dialog.RenameDialog
import com.yichao.evilgodxu.ui.component.rememberTrackSwitchAnchor
import com.yichao.evilgodxu.ui.copyToClipboard
import kotlinx.coroutines.launch

// 竖屏歌曲信息：标题与艺术家随切歌方向整体横移，长按打开复制/重命名/在线搜索菜单。
// 重命名与歌手选择在此闭环，避免把这些表单状态散落到播放器布局里
@Composable
internal fun PortraitTrackInfoSection(
    playbackState: MusicPlaybackState,
    onOpenOnlineSearch: (String) -> Unit,
    onOpenArtistPlaylist: (String) -> Unit,
    onOpenMetadata: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 切歌过渡锚点：标题与艺术家随锚点整体横移，方向来自本次变更的类型
    val trackAnchor = rememberTrackSwitchAnchor(playbackState.currentTrack, playbackState.lastSwitchKind)

    var showMetaMenu by remember { mutableStateOf(false) }
    var menuText by remember { mutableStateOf("") }
    var menuIsTitle by remember { mutableStateOf(true) }
    var showRename by remember { mutableStateOf(false) }
    var renameIsTitle by remember { mutableStateOf(true) }
    var renameInitValue by remember { mutableStateOf("") }
    var renameTargetId by remember { mutableStateOf<Long?>(null) }
    // 点击歌手信息时待选择的歌手候选：多位歌手时弹出选择对话框
    var artistPicker by remember { mutableStateOf<List<String>>(emptyList()) }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 标题与艺术家：切歌时随封面与背景同一节拍整幅横移，方向一致。
        // 文本取自锚点而非当前曲目：退场的那一层要显示上一首的文本，读实时状态会显示成新曲目
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            TrackSwitchTransition(
                anchor = trackAnchor,
                modifier = Modifier.fillMaxWidth(),
            ) { info ->
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    MarqueeInfoLine(
                        text = info.title,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        modifier = Modifier
                            .padding(horizontal = 32.dp)
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {},
                                onLongClick = {
                                    val track = playbackState.currentTrack
                                    if (track != null) {
                                        menuText = track.title
                                        menuIsTitle = true
                                        showMetaMenu = true
                                    }
                                },
                            ),
                    )
                    if (info.id != null) {
                        Spacer(Modifier.height(4.dp))
                        MarqueeInfoLine(
                            text = info.artist,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Normal,
                            color = Color.White,
                            modifier = Modifier
                                .padding(horizontal = 32.dp)
                                .fillMaxWidth()
                                .combinedClickable(
                                    // 多位歌手先弹选择对话框，单一时直接进入该歌手的歌单页
                                    onClick = {
                                        val artists = parseTrackArtists(playbackState.currentTrack?.artist.orEmpty())
                                            .filter { it.isNotBlank() }
                                        if (artists.size > 1) {
                                            artistPicker = artists
                                        } else {
                                            artists.firstOrNull()?.let(onOpenArtistPlaylist)
                                        }
                                    },
                                    onLongClick = {
                                        val artist = playbackState.currentTrack?.artist
                                        if (!artist.isNullOrBlank()) {
                                            menuText = artist
                                            menuIsTitle = false
                                            showMetaMenu = true
                                        }
                                    },
                                ),
                        )
                    }
                }
            }
            MiniContextMenu(
                visible = showMetaMenu,
                onCopy = {
                    showMetaMenu = false
                    copyToClipboard(context, menuText)
                },
                onRename = {
                    showMetaMenu = false
                    val track = playbackState.currentTrack
                    when {
                        track == null -> Unit
                        // 写回音频文件要全部文件访问：缺权限就直接去元数据编辑页，
                        // 那一页打开时会申请；不该让用户先填一遍再被告知写不进去
                        !hasAllFilesAccess() -> onOpenMetadata(track.id)
                        else -> {
                            renameIsTitle = menuIsTitle
                            renameInitValue = menuText
                            renameTargetId = track.id
                            showRename = true
                        }
                    }
                },
                // 标题整体作为唯一关键词；艺术家按多歌手分隔符解析，多位时由菜单下钻选择
                searchTargets = if (menuIsTitle) listOf(menuText) else parseTrackArtists(menuText),
                onSearchTarget = { target ->
                    showMetaMenu = false
                    onOpenOnlineSearch(target)
                },
                onDismiss = { showMetaMenu = false },
            )
        }
    }

    RenameDialog(
        visible = showRename,
        isTitle = renameIsTitle,
        initialValue = renameInitValue,
        onConfirm = { newValue ->
            showRename = false
            val track = playbackState.currentTrack
            if (track != null && track.id == renameTargetId) {
                val updated = if (renameIsTitle) track.copy(title = newValue)
                else track.copy(artist = newValue)
                playbackState.renameTrackMetadata(updated)
                // 手动重命名标题/艺术家后写入音频文件元数据
                scope.launch {
                    MusicMetadataWriter.writeTitleArtist(context, track, updated.title, updated.artist)
                }
            }
        },
        onCancel = { showRename = false },
    )

    // 多位歌手的曲目：点击歌手信息后弹出的歌手选择对话框
    ArtistPickerDialog(
        artists = artistPicker,
        onSelect = { artist ->
            artistPicker = emptyList()
            onOpenArtistPlaylist(artist)
        },
        onDismiss = { artistPicker = emptyList() },
    )
}
