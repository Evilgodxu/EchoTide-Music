package com.yichao.evilgodxu.floatingwindow.miniplayer

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
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
import com.yichao.evilgodxu.data.music.playback.playTrackAt
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.ui.icons.AppIcons
import com.yichao.evilgodxu.ui.component.PlaylistArt
import com.yichao.evilgodxu.ui.component.scrollPlaylistTo
import kotlinx.coroutines.launch

@Composable
internal fun MiniPlaylistPanel(
    playbackState: MusicPlaybackState,
    context: android.content.Context,
    scrollReady: Int,
    onClose: () -> Unit,
) {
    val visibleCount = playbackState.playlist.size.coerceIn(0, MINI_PLAYLIST_MAX_VISIBLE_ROWS)
    val listState = rememberLazyListState()

    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.mini_player_playlist_title),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.music_panel_track_count, playbackState.playlist.size),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .height((visibleCount * 44).dp)
        ) {
            itemsIndexed(
                items = playbackState.playlist,
                key = { _, track -> track.audioUri }
            ) { index, track ->
                val isActive = index == playbackState.currentIndex
                MiniPlaylistRow(
                    track = track,
                    isActive = isActive,
                    isPlaying = isActive && playbackState.isPlaying,
                    isQueued = playbackState.isInPlayNext(track.id),
                    onClick = {
                        // 使用应用级作用域：面板收起离开组合时，播放命令不会被协程取消
                        playbackState.playbackScope.launch { playTrackAt(context, playbackState, index) }
                        onClose()
                    },
                    onFavoriteClick = { playbackState.toggleFavorite(track.id) },
                    onPlayNextClick = { playbackState.togglePlayNext(track) }
                )
            }
        }
        // 展开缩放动画完成（scrollReady 自增）后：始终将当前曲目滚动到列表居中位置；
        // 此时缩放动画已结束，滚动不会与展开动画叠加导致卡顿
        LaunchedEffect(scrollReady) {
            if (scrollReady > 0 && playbackState.currentIndex >= 0 && playbackState.playlist.isNotEmpty()) {
                listState.scrollPlaylistTo(
                    playbackState.currentIndex.coerceIn(0, playbackState.playlist.size - 1),
                    forceCenter = true
                )
            }
        }
        // 切歌时定位：当前曲目不在可视区内才滚动到居中位置，避免反复滚动卡顿
        LaunchedEffect(playbackState.currentTrack?.id) {
            if (scrollReady > 0 && playbackState.currentIndex >= 0 && playbackState.playlist.isNotEmpty()) {
                listState.scrollPlaylistTo(
                    playbackState.currentIndex.coerceIn(0, playbackState.playlist.size - 1)
                )
            }
        }
    }
}

@Composable
private fun MiniPlaylistRow(
    track: MusicTrack,
    isActive: Boolean,
    isPlaying: Boolean,
    isQueued: Boolean,
    onClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    onPlayNextClick: () -> Unit,
) {
    val bg by animateColorAsState(
        targetValue = if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
        else Color.Transparent,
        label = "mini_playlist_bg"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 与完整面板一致：显示专辑封面，播放中叠加动态指示
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
                                label = "mini_wave_$i"
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
                color = if (isActive) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
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

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(
                onClick = onPlayNextClick,
                modifier = Modifier.size(26.dp)
            ) {
                Icon(
                    imageVector = if (isQueued) AppIcons.Remove else AppIcons.Add,
                    contentDescription = stringResource(
                        if (isQueued) R.string.music_panel_cancel_play_next else R.string.music_panel_play_next
                    ),
                    tint = if (isQueued) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }

            IconButton(
                onClick = onFavoriteClick,
                modifier = Modifier.size(26.dp)
            ) {
                Icon(
                    imageVector = if (track.isFavorite) AppIcons.Favorite else AppIcons.FavoriteBorder,
                    contentDescription = null,
                    tint = if (track.isFavorite) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}