package com.yichao.evilgodxu.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import com.yichao.evilgodxu.LocalPlaybackState
import com.yichao.evilgodxu.data.music.metadata.CoverColorCache
import com.yichao.evilgodxu.data.music.metadata.CurrentCoverCache
import com.yichao.evilgodxu.data.music.metadata.LargeCoverStore
import com.yichao.evilgodxu.data.music.metadata.MusicCoverLoader
import com.yichao.evilgodxu.data.music.metadata.extractCoverBackgroundColors
import com.yichao.evilgodxu.data.music.model.MusicTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 封面唯一入口：列表、光碟、轮播、大封面、取色共用。
 * 顺序：内存缓存 → 落盘封面 → 加载器，命中即同步返回。
 * coverRevision 变化时重取，避免旧图一直命中。
 */
@Composable
internal fun rememberSystemThumbnail(track: MusicTrack?, sizePx: Int): ImageBitmap? =
    rememberThumbnailState(track, sizePx).bitmap

/**
 * 封面状态：位图与是否仍在取图
 */
@Immutable
internal data class ThumbnailState(
    val bitmap: ImageBitmap?,
    val pending: Boolean,
)

/** 需要区分取图中与取不到时用此入口 */
@Composable
internal fun rememberThumbnailState(track: MusicTrack?, sizePx: Int): ThumbnailState {
    val context = LocalContext.current
    val audioUri = track?.audioUri
    val coverRevision = LocalPlaybackState.current.coverRevision
    // 初值同步取回，避免闪占位符；键带封面版本号
    val thumbnail = remember(audioUri, track?.id, sizePx, coverRevision) {
        val target = track
        mutableStateOf(
            runCatching {
                target?.let { MusicCoverLoader.cachedThumbnail(it, sizePx) ?: CurrentCoverCache.peek(it.audioUri) }
            }.getOrNull()
        )
    }
    // 同步命中即有结论，否则等异步
    val pending = remember(audioUri, track?.id, sizePx, coverRevision) {
        mutableStateOf(track != null && thumbnail.value == null)
    }
    LaunchedEffect(audioUri, track?.id, sizePx, coverRevision) {
        val target = track
        thumbnail.value = if (target == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    MusicCoverLoader.cachedThumbnail(target, sizePx)
                        // 当前曲落盘封面优先于重新解码
                        ?: CurrentCoverCache.load(context, target.audioUri)
                        ?: MusicCoverLoader.load(context, target, sizePx)
                }.getOrNull()
            }
        }
        pending.value = false
    }
    // 按位图实例记忆包装，避免重复淡入
    val bitmap = thumbnail.value
    return ThumbnailState(
        bitmap = remember(bitmap) { bitmap?.asImageBitmap() },
        pending = pending.value,
    )
}

/**
 * 首页大封面两级取图：低清底图垫底，高清淡入覆盖。
 * 占位底色取同源取色，未命中则由底图现算。
 * 返回值区分取图中与确认取不到，并给出底色与底图。
 */
@Composable
internal fun rememberLargeCoverState(track: MusicTrack?): LargeCoverState {
    val context = LocalContext.current
    val playbackState = LocalPlaybackState.current
    val audioUri = track?.audioUri
    val coverRevision = playbackState.coverRevision
    // 底图与列表行同档，预取过的直接命中
    val baseSizePx = listCoverThumbnailSize(LocalDensity.current)
    // 初值同步取回，命中即首帧高清
    val cover = remember(audioUri, track?.id, coverRevision) {
        val target = track
        mutableStateOf(runCatching { target?.let { LargeCoverStore.peek(it.audioUri) } }.getOrNull())
    }
    // 低清底图：落盘缩略图优先
    val base = remember(audioUri, track?.id, baseSizePx, coverRevision) {
        val target = track
        mutableStateOf(
            runCatching {
                target?.let {
                    CurrentCoverCache.peek(it.audioUri) ?: MusicCoverLoader.cachedThumbnail(it, baseSizePx)
                }
            }.getOrNull()
        )
    }
    // 占位底色与背景同源，可观察
    val sharedColor = track?.let {
        CoverColorCache.get(it.audioUri)?.representative ?: playbackState.restoredBackgroundColorFor(it)
    }
    // 两处都没命中时由底图现算
    var derivedColor by remember(audioUri, track?.id, coverRevision) { mutableStateOf<Color?>(null) }
    // 两级都跑完才算定论
    val settled = remember(audioUri, track?.id, baseSizePx, coverRevision) { mutableStateOf(cover.value != null) }
    LaunchedEffect(audioUri, track?.id, baseSizePx, coverRevision) {
        val target = track
        if (target == null) {
            base.value = null
            cover.value = null
            settled.value = true
            return@LaunchedEffect
        }
        // 一级：补取低清底图，排在高清之前
        if (base.value == null) {
            withContext(Dispatchers.IO) {
                runCatching { MusicCoverLoader.load(context, target, baseSizePx) }.getOrNull()
            }?.let { base.value = it }
        }
        // 二级：高清，取不到则保留底图
        withContext(Dispatchers.IO) {
            runCatching { LargeCoverStore.get(context, target) }.getOrNull()
        }?.let { cover.value = it }
        settled.value = true
    }
    // 兜底底色单独成效果，避免重跑高清
    val baseBitmap = base.value
    LaunchedEffect(audioUri, track?.id, coverRevision, baseBitmap, sharedColor) {
        if (sharedColor != null || derivedColor != null || baseBitmap == null) return@LaunchedEffect
        derivedColor = withContext(Dispatchers.IO) {
            runCatching { extractCoverBackgroundColors(baseBitmap)?.representative }.getOrNull()
        }
    }
    val coverBitmap = cover.value
    return LargeCoverState(
        // 按位图实例记忆包装，避免重复淡入
        cover = remember(coverBitmap) { coverBitmap?.asImageBitmap() },
        pending = !settled.value,
        base = remember(baseBitmap) { baseBitmap?.asImageBitmap() },
        baseColor = sharedColor ?: derivedColor,
    )
}

/**
 * 大封面状态：正图、低清底图与占位底色。
 * pending 为真表示仍在取图，此时应沿用上一张。
 */
@Immutable
internal data class LargeCoverState(
    val cover: ImageBitmap?,
    val pending: Boolean,
    val base: ImageBitmap?,
    val baseColor: Color?,
)
