package com.yichao.evilgodxu.ui.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yichao.evilgodxu.LocalPlaybackState
import com.yichao.evilgodxu.data.music.model.MusicTrack

// 列表行淡入 200ms；防闪靠邻域预取
internal const val LIST_COVER_FADE_IN_MS = 200

// 索引曲目取系统缩略图，否则取内嵌封面，都没有则占位
@Composable
private fun SystemCoverArt(
    track: MusicTrack?,
    modifier: Modifier,
    thumbnailSize: Int,
    placeholderIconSize: Dp,
) {
    val playbackState = LocalPlaybackState.current
    val thumb = rememberSystemThumbnail(track, thumbnailSize)
    LaunchedEffect(track?.id) {
        track?.let { playbackState.requestMetadata(it) }
    }
    // 与首页同一过渡；命中缓存时短路直接落图
    CoverTransition(
        bitmap = thumb,
        contentDescription = track?.title,
        placeholderColor = MaterialTheme.colorScheme.surfaceVariant,
        placeholderIconSize = placeholderIconSize,
        fadeInMillis = LIST_COVER_FADE_IN_MS,
        modifier = modifier,
    )
}

@Composable
internal fun PlaylistArt(track: MusicTrack?, modifier: Modifier = Modifier) {
    // 按实际显示尺寸请求，避免整屏占内存
    SystemCoverArt(
        track = track,
        modifier = modifier,
        thumbnailSize = listCoverThumbnailSize(LocalDensity.current),
        placeholderIconSize = 12.dp,
    )
}
