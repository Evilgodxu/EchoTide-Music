package com.yichao.evilgodxu.ui.component.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.ui.icons.AppIcons
import com.yichao.evilgodxu.ui.component.PlaylistArt
import com.yichao.evilgodxu.ui.component.scrollPlaylistTo
import kotlinx.coroutines.delay

@Composable
internal fun PlaylistOverlay(
    visible: Boolean,
    playbackState: MusicPlaybackState,
    onScan: () -> Unit,
    onTrackSelected: (Int) -> Unit,
    onTrackLongPress: (MusicTrack) -> Unit,
    onDismiss: () -> Unit,
) {
    AnimatedContent(
        targetState = visible,
        transitionSpec = {
            (slideInVertically { it } + fadeIn()).togetherWith(slideOutVertically { it } + fadeOut())
        },
        label = "playlist"
    ) { show ->
        if (show) {
            // 展开就绪：等面板进入动画完成后再定位当前曲目，避免滚动与展开动画叠加卡顿
            var playlistSettled by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                playlistSettled = false
                delay(PLAYLIST_EXPAND_ANIM_MS)
                playlistSettled = true
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.music_panel_playlist_title),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.music_panel_track_count, playbackState.playlist.size),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp
                        )
                        HeaderIconButton(
                            icon = AppIcons.Refresh,
                            onClick = { if (!playbackState.isScanning) onScan() },
                            modifier = Modifier.size(24.dp),
                            enabled = !playbackState.isScanning
                        )
                        HeaderIconButton(
                            icon = AppIcons.Close,
                            onClick = onDismiss,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                if (playbackState.isScanning) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                } else {
                    val listState = rememberLazyListState()
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        itemsIndexed(
                            items = playbackState.playlist,
                            key = { _, track -> track.audioUri }
                        ) { index, track ->
                            val isActive = index == playbackState.currentIndex
                            PlaylistRow(
                                track = track,
                                isActive = isActive,
                                isPlaying = isActive && playbackState.isPlaying,
                                isQueued = playbackState.isInPlayNext(track.id),
                                onClick = { onTrackSelected(index) },
                                onLongClick = { onTrackLongPress(track) },
                                onFavoriteClick = { playbackState.toggleFavorite(track.id) },
                                onPlayNextClick = { playbackState.togglePlayNext(track) }
                            )
                        }
                    }
                    // 面板展开动画完成后：始终将当前曲目滚动到列表居中位置
                    LaunchedEffect(playlistSettled) {
                        if (playlistSettled && playbackState.currentIndex >= 0 && playbackState.playlist.isNotEmpty()) {
                            listState.scrollPlaylistTo(
                                playbackState.currentIndex.coerceIn(0, playbackState.playlist.size - 1),
                                forceCenter = true
                            )
                        }
                    }
                    // 切歌时定位：当前曲目不在可视区内才滚动到居中位置，避免反复滚动卡顿
                    LaunchedEffect(playbackState.currentTrack?.id) {
                        if (playlistSettled && playbackState.currentIndex >= 0 && playbackState.playlist.isNotEmpty()) {
                            listState.scrollPlaylistTo(
                                playbackState.currentIndex.coerceIn(0, playbackState.playlist.size - 1)
                            )
                        }
                    }
                }
            }
        } else {
            Box(modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
internal fun PlaylistRow(
    track: MusicTrack,
    isActive: Boolean,
    isPlaying: Boolean,
    isQueued: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    onPlayNextClick: () -> Unit,
) {
    val bg by animateColorAsState(
        targetValue = if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
        else Color.Transparent,
        label = "playlist_bg"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(6.dp))
        ) {
            PlaylistArt(track = track, modifier = Modifier.fillMaxSize())
            if (isActive && isPlaying) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        repeat(3) { i ->
                            val height by animateFloatAsState(
                                targetValue = 0.4f + kotlin.random.Random.nextFloat() * 0.5f,
                                animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy),
                                label = "wave_$i"
                            )
                            Box(
                                modifier = Modifier
                                    .width(2.dp)
                                    .height((height * 10).dp)
                                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(1.dp))
                            )
                        }
                    }
                }
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = track.title,
                color = if (isActive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal,
                modifier = if (track.title.length > 12) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier
            )
            Text(
                text = track.artist,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        HeaderIconButton(
            icon = if (isQueued) AppIcons.Remove else AppIcons.Add,
            contentDescription = stringResource(
                if (isQueued) R.string.music_panel_cancel_play_next else R.string.music_panel_play_next
            ),
            onClick = onPlayNextClick,
            tint = if (isQueued) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )

        HeaderIconButton(
            icon = if (track.isFavorite) AppIcons.Favorite else AppIcons.FavoriteBorder,
            onClick = onFavoriteClick,
            tint = if (track.isFavorite) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
    }
}

// 播放列表展开进入动画时长：等动画完成后才滚动定位当前曲目，避免动画叠加卡顿
private const val PLAYLIST_EXPAND_ANIM_MS = 300L
