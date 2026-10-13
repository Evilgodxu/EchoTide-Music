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
import com.yichao.evilgodxu.LocalMusicPanelStateHolder
import com.yichao.evilgodxu.data.music.metadata.CoverColorCache
import com.yichao.evilgodxu.data.music.metadata.CurrentCoverCache
import com.yichao.evilgodxu.data.music.metadata.LargeCoverStore
import com.yichao.evilgodxu.data.music.metadata.MusicCoverLoader
import com.yichao.evilgodxu.data.music.metadata.extractCoverBackgroundColors
import com.yichao.evilgodxu.data.music.model.MusicTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 封面显示唯一入口：所有封面显示（列表行、迷你播放器光碟、3D 轮播、首页大封面、沉浸背景取色）都经此处取图。
 *
 * 请求尺寸按展示场景分级（见 ui/component 的封面请求尺寸策略）：
 * 列表行、光碟、刷新预览按控件实际渲染尺寸换算；3D 轮播固定 [CAROUSEL_COVER_THUMBNAIL_SIZE]；
 * 首页大封面另走 [rememberLargeCoverState]（已就位的低清缩略图先出图，内嵌原图长边至 [LargeCoverStore.MAX_EDGE_PX] 后淡入覆盖）；
 * 沉浸背景取色只要 64px 一档。
 *
 * 取图顺序：内存缓存（[MusicCoverLoader.cachedThumbnail]，列表预取据同一判定跳过已就位的项）
 * → 当前曲目的落盘封面（[CurrentCoverCache]，冷启动时跳过系统略缩图查询与内嵌封面解码）
 * → [MusicCoverLoader]（索引曲目读系统媒体库略缩图，非索引曲目解码音频文件的内嵌封面）；
 * 三处都取不到时即由调用方显示占位符。
 *
 * 命中内存缓存时本函数同步返回位图，首帧即是封面、不经过占位符：
 * 列表行不闪占位符靠的正是这条 —— 预取把邻域封面提前放进内存缓存，行滚入视口时直接命中。
 *
 * 重载时机由 [coverRevision] 驱动：封面重写后音频文件的 URI 不变而略缩图已变，
 * 仅靠 URI 作键会让已解码的旧图一直命中。
 *
 * 取图状态按「影响取图的全部输入」作键重建：初值只在状态创建时生效，
 * 不随键重建的话换曲后状态仍持有上一曲的位图，新图到位前显示端一直停在上一曲。
 */
@Composable
internal fun rememberSystemThumbnail(track: MusicTrack?, sizePx: Int): ImageBitmap? =
    rememberThumbnailState(track, sizePx).bitmap

/**
 * 封面取图状态：可显示的位图，以及本次取图是否已有结论。
 *
 * [pending] 为真表示这张封面仍在取图、眼下无图可显示。调用方据此区分「还没取到」与「确认取不到」——
 * 对等图下结论的用途（如沉浸背景取色）两者截然不同：前者要接着等，后者该落到无封面的回落处理。
 */
@Immutable
internal data class ThumbnailState(
    val bitmap: ImageBitmap?,
    val pending: Boolean,
)

/** 需要区分「取图中」与「取不到」的调用方走这里；只要位图的走 [rememberSystemThumbnail] */
@Composable
internal fun rememberThumbnailState(track: MusicTrack?, sizePx: Int): ThumbnailState {
    val context = LocalContext.current
    val audioUri = track?.audioUri
    val coverRevision = LocalMusicPanelStateHolder.current.state.coverRevision
    // 初值同步取回，避免进出页面重建后先闪占位符再出图；
    // 当前曲目另有已落盘的封面（冷启动预读已驻留内存），一并同步取用，使冷启动首帧直接出图。
    // 键带 coverRevision：封面重写已作废缓存与落盘封面，避免把旧图作为初值顶出。
    // 状态持有位图本体而非包装后的 ImageBitmap：命中内存缓存时初值与异步结果正是同一实例，
    // 赋值不触发重组，下游「是否换了一张图」的判定也就不会把命中读成一次换图
    val thumbnail = remember(audioUri, track?.id, sizePx, coverRevision) {
        val target = track
        mutableStateOf(
            runCatching {
                target?.let { MusicCoverLoader.cachedThumbnail(it, sizePx) ?: CurrentCoverCache.peek(it.audioUri) }
            }.getOrNull()
        )
    }
    // 取图结论：初值同步命中的那一张已成结论，否则等异步取图返回（无曲目时本就没有可取的封面）
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
                        // 当前曲目的落盘封面优先于重新解码：冷启动沿用上次切歌时保存的同一张图
                        ?: CurrentCoverCache.load(context, target.audioUri)
                        ?: MusicCoverLoader.load(context, target, sizePx)
                }.getOrNull()
            }
        }
        pending.value = false
    }
    // 位图 → ImageBitmap 的包装按位图实例记忆：asImageBitmap 每次都返回新实例，
    // 逐帧重新包装会让下游「同一张图」的判定永远不成立，内存命中也要白跑一次淡入
    val bitmap = thumbnail.value
    return ThumbnailState(
        bitmap = remember(bitmap) { bitmap?.asImageBitmap() },
        pending = pending.value,
    )
}

/**
 * 首页大封面（竖屏沉浸封面、横屏融合封面）与歌手详情页头图专用取图，两级：
 *
 * 1. **低清底图**：任何已就位的缩略图 —— 当前曲落盘的那张优先（冷启动首帧即有，见 [CurrentCoverCache]），
 *    其次是预取进内存的列表档缩略图；两者都缺席时按列表档补取一次。档位与列表行一致，
 *    取到的就是列表正在用的同一张，没有第二份同物不同尺寸的产出。它铺在 [LargeCoverState.baseColor] 之上；
 * 2. **高清正图**：内存驻留 → 落盘高清档 → 内嵌原图长边至 [LargeCoverStore.MAX_EDGE_PX]
 *    （见 [LargeCoverStore.get]）。从列表直接点选任意曲目时末一步要读一次内嵌图并解码，可能要 1–3 秒，
 *    故不能让它挡住首帧；相邻曲目的预热顺序是 当前曲 → 下一曲 → 上一曲，见播放状态的邻近曲目预取，
 *    预取过的曲目在本级直接命中。
 *
 * 本级不再另查系统略缩图：那一档与第 1 级的来源同物不同尺寸，多查一次只是重复解码，
 * 结果也不会被任何列表复用。低清到高清的升清由调用方（HomeAlbumArt）经 CoverTransition 淡入覆盖完成。
 *
 * 占位底色取与该曲背景同源的一处：内存取色缓存（切歌预取顺手算出）→ 持久化取色结果；
 * 两处都没命中时由已就位的底图现算一段 —— 头图取的是歌手首曲这类从不持久化取色的曲目，
 * 若退回固定的深色底，它会与同源沉浸背景割裂成一块深色，故这里补这一段现算。
 *
 * 不复用 [rememberSystemThumbnail]：它按控件渲染尺寸请求，铺满首屏只能放大渲染而发虚，
 * 且它以单张图为语义，没有这里「底色 + 底图 + 正图」的三级。
 *
 * 取图状态按曲目作键重建，换曲即同步落到新曲目的初值：上一曲的图不会被沿用，
 * 底色、底图与正图也就一定会为新曲目各取一次 —— 否则换曲后画面停在上一曲，直到高清档到位才跳变。
 *
 * 返回值把「还没取到」与「确认取不到」分开，并单列底色与低清底图（见 [LargeCoverState]）：
 * 显示端据此决定是沿用上一张封面还是落到占位——后者是与页面底色同为近黑的色块，换曲取图期间露出来就是一次黑闪。
 */
@Composable
internal fun rememberLargeCoverState(track: MusicTrack?): LargeCoverState {
    val context = LocalContext.current
    val playbackState = LocalMusicPanelStateHolder.current.state
    val audioUri = track?.audioUri
    val coverRevision = playbackState.coverRevision
    // 底图档位与列表行一致：取到的就是列表正在用的那一张，预取过的曲目直接命中内存缓存
    val baseSizePx = listCoverThumbnailSize(LocalDensity.current)
    // 初值同步取回：相邻曲目的高清档已被预热，命中即首帧就是高清
    val cover = remember(audioUri, track?.id, coverRevision) {
        val target = track
        mutableStateOf(runCatching { target?.let { LargeCoverStore.peek(it.audioUri) } }.getOrNull())
    }
    // 低清底图：当前曲落盘的缩略图优先，其次是预取进内存的列表档缩略图
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
    // 占位底色：与该曲背景同源的两处。持久化取色是可观察的，取色落定即跟随重组
    val sharedColor = track?.let {
        CoverColorCache.get(it.audioUri)?.representative ?: playbackState.restoredBackgroundColorFor(it)
    }
    // 上面两处都没命中（如头图取的是歌手首曲，从不持久化取色）时由底图现算一段：
    // 取色源与底图同源，底面就不会与随后铺开的沉浸背景割裂
    var derivedColor by remember(audioUri, track?.id, coverRevision) { mutableStateOf<Color?>(null) }
    // 两级都跑完才算「已定论」：在此之前为空只说明还没取到，而不是这首没有封面
    val settled = remember(audioUri, track?.id, baseSizePx, coverRevision) { mutableStateOf(cover.value != null) }
    LaunchedEffect(audioUri, track?.id, baseSizePx, coverRevision) {
        val target = track
        if (target == null) {
            base.value = null
            cover.value = null
            settled.value = true
            return@LaunchedEffect
        }
        // 一级：同步没取到底图才补一次。排在高清之前 —— 先有底色与低清可看，高清随后覆盖上去
        if (base.value == null) {
            withContext(Dispatchers.IO) {
                runCatching { MusicCoverLoader.load(context, target, baseSizePx) }.getOrNull()
            }?.let { base.value = it }
        }
        // 二级：高清。无可用封面时保持已出的底图，不把已就位的一张撤下
        withContext(Dispatchers.IO) {
            runCatching { LargeCoverStore.get(context, target) }.getOrNull()
        }?.let { cover.value = it }
        settled.value = true
    }
    // 现算兜底底色另起一个效果：它依赖底图与共享取色（两者都会先后就位），
    // 并入上面那个会把「底图到位」也变成两级取图的重启键，白跑一轮高清取图
    val baseBitmap = base.value
    LaunchedEffect(audioUri, track?.id, coverRevision, baseBitmap, sharedColor) {
        if (sharedColor != null || derivedColor != null || baseBitmap == null) return@LaunchedEffect
        derivedColor = withContext(Dispatchers.IO) {
            runCatching { extractCoverBackgroundColors(baseBitmap)?.representative }.getOrNull()
        }
    }
    val coverBitmap = cover.value
    return LargeCoverState(
        // 包装按位图实例记忆：同一张图命中时不产生新实例，下游「是否换了一张」的判定才成立，不会白跑一次淡入
        cover = remember(coverBitmap) { coverBitmap?.asImageBitmap() },
        pending = !settled.value,
        base = remember(baseBitmap) { baseBitmap?.asImageBitmap() },
        baseColor = sharedColor ?: derivedColor,
    )
}

/**
 * 大封面的取图状态。
 *
 * [cover] 为高清正图；[base] 为低清底图、[baseColor] 为占位底色 —— 正图未就位时由调用方把两者叠在
 * 一起（底色在下、底图在上），正图到位后再淡入覆盖，取图期间既不空等，升清也不跳变。
 * [baseColor] 为空即由调用方退到其默认底色。
 *
 * [pending] 表示这张曲目的封面仍在取图中且眼下无正图可显示：显示端据此先沿用上一张已就位的封面 ——
 * 占位是与页面底色同为近黑的色块，换曲时直接露出来就是一次黑闪。确认取不到封面（[pending] 为假
 * 且 [cover] 为空）时才轮到占位。
 */
@Immutable
internal data class LargeCoverState(
    val cover: ImageBitmap?,
    val pending: Boolean,
    val base: ImageBitmap?,
    val baseColor: Color?,
)
