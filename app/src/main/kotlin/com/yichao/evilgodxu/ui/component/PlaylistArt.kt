package com.yichao.evilgodxu.ui.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yichao.evilgodxu.LocalMusicPanelStateHolder
import com.yichao.evilgodxu.data.music.model.MusicTrack

// 列表行封面的淡入时长：取 Coil crossfade 的默认档（200ms）。
// 不沿用首页大封面的 500ms —— 那档是给「两次取图之间的清晰度差」留的节奏，
// 列表行只有 28dp，同屏十余行还可能同时到达，更慢的淡入会叠成整片涌动。
// 真正让滚动不闪的是邻域预取（见 CoverPrefetch）把封面提前放进内存缓存：
// 大多行首帧即同步命中，压根走不到这条淡入路径上
private const val LIST_COVER_FADE_IN_MS = 200

// 封面显示：索引曲目取系统略缩图，非索引曲目取音频文件的内嵌封面（见 rememberSystemThumbnail）。
// 取不到即占位符——当前曲目的一张缩略图会落盘供冷启动复用（见 CurrentCoverCache），但不回退在线封面地址：
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
    // 与音乐面板光碟同一过渡（见 CoverTransition）：底层常驻 + 上层淡入。
    // 首帧即命中内存缓存时取到的是同一个位图实例，过渡被短路成「直接落图」；
    // 只有异步取到、此前确为空的那一次才淡入 —— 与 Coil 的过渡口径一致：
    // 命中内存缓存不做过渡，否则每次滚回同一行都淡入一次，那才是闪烁
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
    // 列表行封面很小，按实际显示尺寸请求（见 listCoverThumbnailSize）：
    // 固定请求 256px 会让每行多占约 9 倍内存，并使内存缓存容纳不下整屏列表
    SystemCoverArt(
        track = track,
        modifier = modifier,
        thumbnailSize = listCoverThumbnailSize(LocalDensity.current),
        placeholderIconSize = 12.dp,
    )
}
