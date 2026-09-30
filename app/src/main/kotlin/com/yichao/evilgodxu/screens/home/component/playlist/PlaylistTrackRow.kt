package com.yichao.evilgodxu.screens.home.component.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.ui.icons.AppIcons
import com.yichao.evilgodxu.ui.component.PlaylistArt

// 歌单曲目行：封面 + 主次文字 + 排序手柄 + 收藏
@Composable
internal fun PlaylistTrackRow(
    track: MusicTrack,
    isActive: Boolean,
    isPlaying: Boolean,
    isLiked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    modifier: Modifier = Modifier,
    isDragging: Boolean = false,
    dragHandleModifier: Modifier = Modifier,
) {
    // 拖拽时背景变白，前景文字同步切换为深色保证可读性
    val fg = if (isDragging) Color(0xFF1A1A1A) else Color.White
    val fgDim = if (isDragging) Color(0xFF1A1A1A).copy(alpha = 0.6f) else Color.White.copy(alpha = 0.6f)
    Row(
        modifier = modifier
            .then(
                if (isDragging) Modifier.background(Color.White, RoundedCornerShape(8.dp))
                else Modifier
            )
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            PlaylistArt(track = track, modifier = Modifier.fillMaxSize())
            if (isActive) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (isPlaying) AppIcons.Pause else AppIcons.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = track.title,
                color = if (isDragging) fg else if (isActive) MaterialTheme.colorScheme.primary else Color.White,
                fontSize = 12.sp,
                lineHeight = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = track.artist,
                color = fgDim,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 排序调整手柄：长按后垂直拖拽实时变更歌单内播放次序，位于收藏按钮左侧
        Box(
            modifier = Modifier
                .size(30.dp)
                .then(dragHandleModifier),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = AppIcons.DragHandle,
                contentDescription = stringResource(R.string.playlist_sort_handle),
                tint = fgDim,
                modifier = Modifier.size(20.dp),
            )
        }
        IconButton(onClick = onFavoriteClick, modifier = Modifier.size(30.dp)) {
            Icon(
                imageVector = if (isLiked) AppIcons.Favorite else AppIcons.FavoriteBorder,
                contentDescription = stringResource(R.string.music_panel_favorite),
                tint = if (isDragging) fg else Color.White,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
