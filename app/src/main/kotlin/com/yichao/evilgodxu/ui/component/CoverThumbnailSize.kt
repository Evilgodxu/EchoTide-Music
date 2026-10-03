package com.yichao.evilgodxu.ui.component

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yichao.evilgodxu.data.music.metadata.MusicCoverLoader
import kotlin.math.ceil

// 封面请求尺寸策略：按展示场景分级，统一由 rememberSystemThumbnail / rememberLargeCoverState 落实。
//
// 系统媒体略缩图按请求尺寸解码，而请求尺寸同时是内存缓存的键：它既决定单张图占多少内存，
// 也决定同一首歌会被解码几次。此前列表行与面板光碟一律请求 256/512px —— 一张 28dp 的封面
// 在 3x 屏上只需 84px，256px 让每行多占约 9 倍内存，并把内存缓存能容纳的封面数压到可见列表量级，
// 退出一屏再回来就得重新取图（表现为滚动与进出页面时的闪烁与等待）。
//
// 各档位口径：
// - 列表行：按行内实际显示尺寸换算（[listCoverThumbnailSize]）
// - 迷你播放器、音乐面板光碟、刷新预览：按控件实际渲染尺寸换算（[coverThumbnailSize]）
// - 3D 轮播：统一取 [CAROUSEL_COVER_THUMBNAIL_SIZE]，不随密度换算
// - 首页大封面：分两级 —— 先系统最大档略缩图出图，再解码内嵌原图高清档替换（见 LargeCoverStore）

// 列表行封面的显示边长上限（各行按 22–28dp 呈现）。所有列表行共用这一个上限而非各自的行内尺寸：
// 同一首歌不会因所在行的封面差几个像素被重复解码、重复驻留
private val LIST_COVER_MAX_DP = 28.dp

// 分档步长：请求尺寸向上取整到该步长的整数倍，保持为一个随密度变化的粗档位而非任意像素值。
// 向上取整保证请求尺寸不低于显示尺寸，封面不会被放大渲染
private const val THUMBNAIL_SIZE_STEP_PX = 32

/** 3D 轮播封面档位：居中封面约占面板高度一半且同屏 7 张，固定取系统略缩图的上限档，不随尺寸与密度换算 */
internal const val CAROUSEL_COVER_THUMBNAIL_SIZE = MusicCoverLoader.SYSTEM_THUMBNAIL_MAX_SIZE_PX

/** 列表行封面的请求边长（像素）：按 [LIST_COVER_MAX_DP] 换算实际显示尺寸后分档 */
internal fun listCoverThumbnailSize(density: Density): Int =
    coverThumbnailSize(density, LIST_COVER_MAX_DP)

/** 按控件实际渲染尺寸 [displaySize] 换算封面请求边长（像素） */
internal fun coverThumbnailSize(density: Density, displaySize: Dp): Int {
    val displayPx = with(density) { displaySize.roundToPx() }
    val buckets = ceil(displayPx.toDouble() / THUMBNAIL_SIZE_STEP_PX).toInt()
    return buckets.coerceAtLeast(1) * THUMBNAIL_SIZE_STEP_PX
}
