package com.yichao.evilgodxu.ui.component

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yichao.evilgodxu.data.music.metadata.MusicCoverLoader
import kotlin.math.ceil

// 封面请求尺寸按场景分级：尺寸即缓存键，决定内存与解码次数
// 列表行与迷你光碟按实际显示尺寸换算
// 3D 轮播固定取系统上限档，首页大封面另走高清档

// 列表行封面显示上限 28dp，全列表共用
private val LIST_COVER_MAX_DP = 28.dp

// 请求尺寸向上取整到 32px 步长，不低于显示尺寸
private const val THUMBNAIL_SIZE_STEP_PX = 32

/** 3D 轮播固定取系统上限档 */
internal const val CAROUSEL_COVER_THUMBNAIL_SIZE = MusicCoverLoader.SYSTEM_THUMBNAIL_MAX_SIZE_PX

/** 列表行封面请求边长 */
internal fun listCoverThumbnailSize(density: Density): Int =
    coverThumbnailSize(density, LIST_COVER_MAX_DP)

/** 按控件显示尺寸换算请求边长 */
internal fun coverThumbnailSize(density: Density, displaySize: Dp): Int {
    val displayPx = with(density) { displaySize.roundToPx() }
    val buckets = ceil(displayPx.toDouble() / THUMBNAIL_SIZE_STEP_PX).toInt()
    return buckets.coerceAtLeast(1) * THUMBNAIL_SIZE_STEP_PX
}
