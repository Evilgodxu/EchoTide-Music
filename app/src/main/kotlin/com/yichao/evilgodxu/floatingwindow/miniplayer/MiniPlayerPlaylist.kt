package com.yichao.evilgodxu.floatingwindow.miniplayer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.data.music.playback.playTrackAt
import com.yichao.evilgodxu.ui.component.CoverPrefetch
import com.yichao.evilgodxu.ui.component.PlaylistArt
import com.yichao.evilgodxu.ui.component.edgeFadeInBrush
import com.yichao.evilgodxu.ui.component.scrollPlaylistTo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 展开后的播放列表。
 *
 * 迷你播放器窗口只有紧凑条的宽度，故行内只留封面、歌名与歌手，不挂收藏/稍后再听这类次级操作——
 * 那些属于应用内的歌单页。点击即切歌，列表自行跟随当前曲目。
 */
@Composable
internal fun MiniPlayerPlaylist(
    playbackState: MusicPlaybackState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    // 展开即定位一次：本列表仅在展开时进入组合，故首次组合等同于展开
    val pendingCenter = remember { mutableStateOf(true) }

    // 展开时当前曲目滚至列表中央；切歌后仅当当前项不在可视区才滚动过去，避免反复滚动
    LaunchedEffect(playbackState.currentTrack?.id) {
        val index = playbackState.currentIndex
        if (index < 0 || playbackState.playlist.isEmpty()) return@LaunchedEffect
        val target = index.coerceIn(0, playbackState.playlist.lastIndex)
        if (!pendingCenter.value) {
            listState.scrollPlaylistTo(target)
            return@LaunchedEffect
        }
        pendingCenter.value = false
        // 窗口高度正在补间，视口未定；等动画结束后再居中，否则偏移按中途视口算出会偏位
        delay(MINI_PLAYLIST_ANIM_MS)
        listState.scrollPlaylistTo(target, forceCenter = true)
    }
    // 封面提前取图：窄列里一屏也有十余行，滚动进入视口时不再先闪占位符
    CoverPrefetch(listState) { index -> playbackState.playlist.getOrNull(index) }

    LazyColumn(
        state = listState,
        modifier = modifier.topFadeMask(),
        contentPadding = PaddingValues(
            horizontal = 4.dp,
            vertical = MINI_PLAYLIST_CONTENT_PADDING_V_DP.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(MINI_PLAYLIST_ROW_SPACING_DP.dp),
    ) {
        itemsIndexed(
            items = playbackState.playlist,
            key = { _, track -> track.audioUri },
        ) { index, track ->
            MiniPlayerPlaylistRow(
                track = track,
                isActive = index == playbackState.currentIndex,
                onClick = { scope.launch { playTrackAt(context, playbackState, index) } },
            )
        }
    }
}

// 列表顶边渐隐蒙层：列表视口紧贴控制栏，行在顶边被硬裁会像被切断，故按平滑曲线把顶边淡出。
// 蒙层由 saveLayer 限定在本节点内，DstIn 只作用于列表内容，不波及控制栏与卡片底色
private fun Modifier.topFadeMask(): Modifier = drawWithCache {
    val band = MINI_PLAYLIST_TOP_FADE_DP.dp.toPx()
    // DstIn 只取蒙层的透明度，RGB 不参与合成，实色段用黑色即可
    val brush = edgeFadeInBrush(
        color = Color.Black,
        end = if (size.height > 0f) (band / size.height).coerceIn(0f, 1f) else 0f,
    )
    onDrawWithContent {
        drawIntoCanvas { canvas -> canvas.saveLayer(Rect(Offset.Zero, size), Paint()) }
        drawContent()
        drawRect(brush = brush, size = size, blendMode = BlendMode.DstIn)
        drawIntoCanvas { canvas -> canvas.restore() }
    }
}

// 列表行：当前曲目以强调色与底色标出，其余行沿用常规文本色
@Composable
private fun MiniPlayerPlaylistRow(
    track: MusicTrack,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(MINI_PLAYLIST_ROW_HEIGHT_DP.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                else Color.Transparent
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(
                horizontal = MINI_PLAYLIST_ROW_PADDING_H_DP.dp,
                vertical = MINI_PLAYLIST_ROW_PADDING_V_DP.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(MINI_PLAYLIST_ART_DP.dp)
                .clip(RoundedCornerShape(6.dp)),
        ) {
            PlaylistArt(track = track, modifier = Modifier.fillMaxSize())
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                color = if (isActive) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                fontSize = 11.sp,
                lineHeight = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal,
            )
            Text(
                text = track.artist,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 9.sp,
                lineHeight = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}