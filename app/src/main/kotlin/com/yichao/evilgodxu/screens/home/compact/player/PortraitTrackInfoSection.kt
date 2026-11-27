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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yichao.evilgodxu.data.music.metadata.MusicMetadataWriter
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.data.music.playback.parseTrackArtists
import com.yichao.evilgodxu.data.settings.LyricLayoutDefaults
import com.yichao.evilgodxu.data.settings.homePortraitLyricLayoutFlow
import com.yichao.evilgodxu.permission.ensureTrackFileWritable
import com.yichao.evilgodxu.permission.rememberAllFilesAccessRequest
import com.yichao.evilgodxu.screens.home.component.dialog.ArtistPickerDialog
import com.yichao.evilgodxu.screens.home.component.player.MarqueeInfoLine
import com.yichao.evilgodxu.ui.component.player.MiniContextMenu
import com.yichao.evilgodxu.ui.component.player.columnAlignment
import com.yichao.evilgodxu.ui.component.player.lineTextAlign
import com.yichao.evilgodxu.ui.component.TrackSwitchTransition
import com.yichao.evilgodxu.ui.component.dialog.RenameDialog
import com.yichao.evilgodxu.ui.component.rememberTrackSwitchAnchor
import com.yichao.evilgodxu.ui.copyToClipboard
import kotlinx.coroutines.launch

// 竖屏歌曲信息：切歌横移，长按出菜单；表单状态在此闭环
@Composable
internal fun PortraitTrackInfoSection(
    playbackState: MusicPlaybackState,
    onOpenOnlineSearch: (String) -> Unit,
    onOpenArtistPlaylist: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val requestAllFilesAccess = rememberAllFilesAccessRequest()
    // 对齐与歌词共用排版设置，版面保持一致
    val homePortraitLayout by context.homePortraitLyricLayoutFlow()
        .collectAsStateWithLifecycle(initialValue = LyricLayoutDefaults.HOME_PORTRAIT_LAYOUT)
    val infoAlignment = homePortraitLayout.alignment
    val infoTextAlign = infoAlignment.lineTextAlign
    // 过渡锚点：标题与艺术家随锚点横移
    val trackAnchor = rememberTrackSwitchAnchor(playbackState.currentTrack, playbackState.lastSwitchKind)

    var showMetaMenu by remember { mutableStateOf(false) }
    var menuText by remember { mutableStateOf("") }
    var menuIsTitle by remember { mutableStateOf(true) }
    var showRename by remember { mutableStateOf(false) }
    var renameIsTitle by remember { mutableStateOf(true) }
    var renameInitValue by remember { mutableStateOf("") }
    var renameTargetId by remember { mutableStateOf<Long?>(null) }
    // 待选择的歌手候选，多位时弹窗
    var artistPicker by remember { mutableStateOf<List<String>>(emptyList()) }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 文本取自锚点，退场层才能显示上一首
        Column(horizontalAlignment = infoAlignment.columnAlignment, modifier = Modifier.fillMaxWidth()) {
            TrackSwitchTransition(
                anchor = trackAnchor,
                modifier = Modifier.fillMaxWidth(),
            ) { info ->
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = infoAlignment.columnAlignment,
                ) {
                    MarqueeInfoLine(
                        text = info.title,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        textAlign = infoTextAlign,
                        modifier = Modifier
                            .padding(horizontal = 32.dp)
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {},
                                onLongClick = {
                                    // 重命名要写文件，无权限先申请再展开
                                    val track = playbackState.currentTrack
                                    if (track != null && ensureTrackFileWritable(requestAllFilesAccess)) {
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
                            textAlign = infoTextAlign,
                            modifier = Modifier
                                .padding(horizontal = 32.dp)
                                .fillMaxWidth()
                                .combinedClickable(
                                    // 多位先弹选择，单一直接进歌单
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
                                        val track = playbackState.currentTrack
                                        if (track != null && !track.artist.isNullOrBlank() &&
                                            ensureTrackFileWritable(requestAllFilesAccess)
                                        ) {
                                            menuText = track.artist
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
                    playbackState.currentTrack?.let { track ->
                        renameIsTitle = menuIsTitle
                        renameInitValue = menuText
                        renameTargetId = track.id
                        showRename = true
                    }
                },
                // 标题作唯一关键词；艺术家按分隔符拆
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
                // 重命名后写入音频文件元数据
                scope.launch {
                    MusicMetadataWriter.writeTitleArtist(context, track, updated.title, updated.artist)
                }
            }
        },
        onCancel = { showRename = false },
    )

    // 点击多位歌手后弹出的选择框
    ArtistPickerDialog(
        artists = artistPicker,
        onSelect = { artist ->
            artistPicker = emptyList()
            onOpenArtistPlaylist(artist)
        },
        onDismiss = { artistPicker = emptyList() },
    )
}
