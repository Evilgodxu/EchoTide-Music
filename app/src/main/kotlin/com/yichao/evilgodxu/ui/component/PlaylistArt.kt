package com.yichao.evilgodxu.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yichao.evilgodxu.LocalMusicPanelStateHolder
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.ui.icons.AppIcons

// 封面显示：索引曲目取系统略缩图，非索引曲目取音频文件的内嵌封面（见 rememberSystemThumbnail）。
// 取不到即占位符——应用不落盘封面缓存，也不回退在线封面地址：
// 在线曲目落盘入库后由系统的媒体扫描生成略缩图，此前的最终刷新会驱动本组件重新取图。
@Composable
private fun SystemCoverArt(
    track: MusicTrack?,
    modifier: Modifier,
    thumbnailSize: Int,
    placeholderIconSize: Dp,
) {
    val stateHolder = LocalMusicPanelStateHolder.current
    val thumb = rememberSystemThumbnail(track, thumbnailSize)
    LaunchedEffect(track?.id) {
        track?.let { stateHolder.state.requestMetadata(it) }
    }
    if (thumb != null) {
        Image(
            bitmap = thumb,
            contentDescription = track?.title,
            contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.High,
            modifier = modifier.background(Color.Black),
        )
    } else {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = AppIcons.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(placeholderIconSize)
            )
        }
    }
}

@Composable
internal fun PlaylistArt(track: MusicTrack?, modifier: Modifier = Modifier) {
    // 列表行略缩图很小，256px 系统略缩图已足够
    SystemCoverArt(track, modifier, thumbnailSize = 256, placeholderIconSize = 12.dp)
}