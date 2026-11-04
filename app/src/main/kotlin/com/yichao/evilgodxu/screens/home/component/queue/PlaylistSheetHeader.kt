package com.yichao.evilgodxu.screens.home.component.queue

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.LocalMetadataEnricher
import com.yichao.evilgodxu.LocalPlaylistRefresher
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.data.music.playback.PlaylistSortField
import com.yichao.evilgodxu.ui.component.ExpandDirection
import com.yichao.evilgodxu.ui.component.ExpandPicker
import com.yichao.evilgodxu.ui.component.player.HeaderIconButton
import com.yichao.evilgodxu.ui.icons.AppIcons
import kotlinx.coroutines.launch

// 播放列表面板头部：标题、当前歌单快捷切换、曲目数、刷新、排序与关闭。
// 排序作用于当前播放结果（默认队列或其过滤结果），仅浏览其它歌单时按钮置灰
@Composable
internal fun PlaylistSheetHeader(
    playbackState: MusicPlaybackState,
    trackCount: Int,
    viewedName: String?,
    followsQueue: Boolean,
    sortMenuMaxHeight: Dp,
    onOpenSwitcher: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playlistRefresher = LocalPlaylistRefresher.current
    val metadataEnricher = LocalMetadataEnricher.current
    val sortEnabled = followsQueue && !playbackState.isScanning

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.music_panel_playlist_title),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(8.dp))
        // 歌单副标题：浅色小字常驻显示当前浏览的歌单，点击快捷切换
        Text(
            text = viewedName ?: stringResource(R.string.playlist_switch_default),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onOpenSwitcher)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = pluralStringResource(R.plurals.music_panel_track_count, trackCount, trackCount),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier.padding(end = 4.dp),
            )
            HeaderIconButton(
                icon = AppIcons.Refresh,
                onClick = {
                    if (!playbackState.isScanning) {
                        scope.launch {
                            playlistRefresher.refresh(
                                context, playbackState, restoreCurrent = true
                            ) {
                                // 刷新后后台加载封面与歌词
                                scope.launch {
                                    metadataEnricher.enrichAndCleanup(
                                        context, playbackState
                                    )
                                }
                            }
                        }
                    }
                },
                modifier = Modifier.size(28.dp),
                enabled = !playbackState.isScanning,
            )
            ExpandPicker(
                options = PlaylistSortField.entries,
                selected = playbackState.playlistSortField,
                enabled = sortEnabled,
                expandDirection = ExpandDirection.Down,
                // 排序按钮靠面板右缘，菜单右对齐避免溢出屏幕
                horizontalAlignment = Alignment.End,
                maxMenuHeight = sortMenuMaxHeight,
                header = {
                    // 方向切换：点击即时生效且不收起，便于连续调整字段与方向
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                playbackState.setPlaylistSort(
                                    playbackState.playlistSortField,
                                    !playbackState.playlistSortDescending,
                                )
                            }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        // 排序方向取 sync_alt 旋转 90° 后的上下双向箭头：单向上/下箭头易被读作页面导航而非排序。
                        // 整套轮廓压暗、仅高亮当前方向的一支（原左向笔画旋转后朝上，原右向笔画旋转后朝下）
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .rotate(90f),
                        ) {
                            Icon(
                                imageVector = AppIcons.SyncAlt,
                                contentDescription = stringResource(
                                    if (playbackState.playlistSortDescending) R.string.music_panel_sort_descending
                                    else R.string.music_panel_sort_ascending
                                ),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.fillMaxSize(),
                            )
                            Icon(
                                imageVector = if (playbackState.playlistSortDescending) AppIcons.SyncAltArrowRight
                                else AppIcons.SyncAltArrowLeft,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                },
                trigger = {
                    Box(
                        modifier = Modifier.size(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = AppIcons.Sort,
                            contentDescription = stringResource(R.string.music_panel_sort),
                            modifier = Modifier.size(21.dp),
                            tint = if (sortEnabled) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                        )
                    }
                },
                itemContent = { field, _ ->
                    Text(
                        text = stringResource(sortFieldLabelRes(field)),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                },
                onItemClick = { field ->
                    playbackState.setPlaylistSort(field, playbackState.playlistSortDescending)
                },
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = AppIcons.Close,
                    contentDescription = stringResource(R.string.home_player_close_playlist),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

// 排序字段对应的文案资源
private fun sortFieldLabelRes(field: PlaylistSortField): Int = when (field) {
    PlaylistSortField.DEFAULT -> R.string.music_panel_sort_default
    PlaylistSortField.FAVORITE -> R.string.music_panel_sort_by_favorite
    PlaylistSortField.MODIFIED_TIME -> R.string.music_panel_sort_modified
    PlaylistSortField.TITLE -> R.string.music_panel_sort_by_title
    PlaylistSortField.ARTIST -> R.string.music_panel_sort_by_artist
    PlaylistSortField.ALBUM -> R.string.music_panel_sort_by_album
    PlaylistSortField.DURATION -> R.string.music_panel_sort_by_duration
}
