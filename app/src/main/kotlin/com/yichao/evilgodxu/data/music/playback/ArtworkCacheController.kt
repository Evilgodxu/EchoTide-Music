package com.yichao.evilgodxu.data.music.playback

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.yichao.evilgodxu.data.music.metadata.CoverBackgroundColors
import com.yichao.evilgodxu.data.music.metadata.CoverColorCache
import com.yichao.evilgodxu.data.music.metadata.CoverSkipRegistry
import com.yichao.evilgodxu.data.music.metadata.CurrentCoverCache
import com.yichao.evilgodxu.data.music.metadata.EmbeddedCoverCache
import com.yichao.evilgodxu.data.music.metadata.LargeCoverStore
import com.yichao.evilgodxu.data.music.metadata.MusicCoverLoader
import com.yichao.evilgodxu.data.music.metadata.SystemThumbnailCache
import com.yichao.evilgodxu.data.music.metadata.extractCoverBackgroundColors
import com.yichao.evilgodxu.data.music.model.MusicTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 封面缓存预热：切歌后的缩略图落盘与背景取色，以及扫描完成后的封面预热。
 *
 * 预取任务按「新一轮先取消上一轮」收敛并发上界，不随切歌次数累积：
 * 快速切歌时上一轮被取消、下一轮尚未启动，等于完全不预取；其余时候只有一个预取协程在跑。
 */
internal class ArtworkCacheController(
    private val scope: () -> CoroutineScope,
    private val context: () -> Context?,
    private val currentTrack: () -> MusicTrack?,
    private val playlist: () -> List<MusicTrack>,
    private val libraryTracks: () -> List<MusicTrack>,
    private val onColorsReady: (uri: String, colors: CoverBackgroundColors) -> Unit,
    private val onInvalidatePersistedColors: () -> Unit,
) {

    private companion object {
        // 切歌预取内嵌封面前的稳定期：快速连点、在列表里来回挑歌时相邻两次切歌往往不足 2 秒，
        // 预取刚起步就被下一次切歌作废，白烧一次内嵌图读取与高清解码；等曲目稳定下来再取，
        // 快速切歌期间等于完全不预取。窗口取宽一些更稳健 —— 代价只是正常听歌时预热晚 2 秒开始
        const val COVER_PREFETCH_SETTLE_MS = 2_000L
    }

    // 封面写入成功后自增，通知封面组件强制重载最新封面
    var coverRevision by mutableIntStateOf(0)

    // 切歌预取的高清封面任务：每次切歌先取消上一轮，再等稳定期后开始。
    //
    // 由此得到的并发上界：
    // - 快速切歌（间隔短于稳定期）时，上一轮被取消、下一轮还没启动 —— 等于完全不预取；
    // - 其余任何时候都只有一个预取协程在跑，且它逐首串行解码，不会堆积；
    // - 与显示端叠加也只有「当前曲的一张 + 预取的一张」：显示端按曲目去重拿解码，
    //   不排在预取后面（见 LargeCoverStore.get）。
    // 取消会中断到哪一步：已经开始的平台调用无法打断（MediaMetadataRetriever 与 ImageDecoder
    // 都不接受取消信号），但重活被卡在可中断的接缝上 —— 读取期间被取消就跳过最重的缩放解码
    // （见 EmbeddedCoverReader.readFitted），解码期间被取消就跳过落盘编码（见 LargeCoverStore.loadHighRes），
    // 尚未开始的相邻曲目直接不再启动。数量有上界、不随切歌次数累积
    private var coverPrefetchJob: Job? = null

    // 扫描后的封面预热任务：新一轮扫描先取消上一轮，避免旧库的专辑继续占用解码
    private var artworkWarmupJob: Job? = null

    /**
     * 切歌后的封面预热：落盘该曲缩略图并持久化背景取色，同时预取首页大图高清档。
     *
     * 冷启动首帧可直读落盘封面出图，不必再查系统略缩图或解码音频内嵌封面；后台切歌
     * （首页未展示、无人取色）同样生效。高清档按「当前曲 → 下一曲 → 上一曲」串行预取，
     * 首页大图与沉浸背景现场取图期间只能先显示模糊缩略图与上一首色调，预取后往返切歌直接命中。
     */
    fun cacheCurrentCoverAndColor(track: MusicTrack) {
        val context = context() ?: return
        scope().launch {
            val bitmap = CurrentCoverCache.ensure(context, track.audioUri) {
                MusicCoverLoader.load(context, track, CurrentCoverCache.THUMBNAIL_SIZE)
            } ?: return@launch
            // 异步取图期间可能已切走：非当前曲目的取色结果落盘会顶掉当前曲目的恢复色
            if (currentTrack()?.audioUri != track.audioUri) return@launch
            // 取色优先用预取结果：它与首页大图同源，落盘的三段色因此与显示端实时背景完全一致；
            // 预取未就绪（首曲、快速连切）时再由这张落盘缩略图现算，两条路径共用同一取色入口
            val colors = CoverColorCache.get(track.audioUri)
                ?: extractCoverBackgroundColors(bitmap)
                ?: return@launch
            onColorsReady(track.audioUri, colors)
        }
        coverPrefetchJob?.cancel()
        coverPrefetchJob = scope().launch {
            delay(COVER_PREFETCH_SETTLE_MS)
            LargeCoverStore.prefetch(context, neighboringTracks(track))
        }
    }

    /**
     * 扫描完成后的显示预热。
     *
     * 封面按专辑去重取：同专辑的曲目共用一张图，逐曲取等于把同一张图解码多遍。
     * 取色只算当前曲目——首页背景只有它在消费，全库取色没有读它的人。
     * 与歌词补全由调用方并行发起：两者互不依赖，谁先就绪谁先可见。
     */
    fun warmUpArtwork(context: Context) {
        val tracks = libraryTracks()
        if (tracks.isEmpty()) return
        currentTrack()?.let(::cacheCurrentCoverAndColor)
        val currentId = currentTrack()?.id
        artworkWarmupJob?.cancel()
        artworkWarmupJob = scope().launch {
            tracks.asSequence()
                .filter { it.id != currentId }
                .distinctBy { if (it.albumId > 0L) "album:${it.albumId}" else "track:${it.id}" }
                .forEach { representative ->
                    if (!isActive) return@launch
                    MusicCoverLoader.prefetch(context, representative, CurrentCoverCache.THUMBNAIL_SIZE)
                }
        }
    }

    // 队列中的当前曲、下一曲、上一曲：按播放队列顺序取相邻项。
    // 预取是串行的（解码很重），顺序即优先级：当前曲先保证高清呈现，其次下一曲提前预热，
    // 最后才是上一曲 —— 顺序播放的用户绝大多数时候只往一个方向走。
    // 不走 nextIndex/previousIndex —— 随机播放下它们是随机抽取，预取目标与真实下一首对不上
    private fun neighboringTracks(track: MusicTrack): List<MusicTrack> {
        val queue = playlist()
        val index = queue.indexOfFirst { it.id == track.id }
        if (index < 0) return listOf(track)
        return listOfNotNull(queue.getOrNull(index), queue.getOrNull(index + 1), queue.getOrNull(index - 1))
    }

    /**
     * 封面写入成功后的整体作废：旧图、旧色调与旧结论均已失效。
     *
     * 作废索引曲目的系统略缩图缓存、非索引曲目的内嵌封面缓存、首页大封面缓存、背景取色缓存与
     * 落盘的背景取色结果、当前曲目的落盘封面，并解除「无封面可取」的跳过标记。
     *
     * 全部作废完成后才自增版本号：显示端的重新取图由版本号驱动，若把落盘档的清除丢到后台与版本号并发，
     * 重新取图会命中尚未删净的旧档（大封面的内存驻留被逐出后正好回落到这一档），封面停在旧图不再刷新。
     * 整段置于 NonCancellable：作废是「新封面已写入」的必然结论，不随调用方是否还在而取舍。
     */
    suspend fun bumpCoverRevision() {
        SystemThumbnailCache.clear()
        EmbeddedCoverCache.clear()
        CoverSkipRegistry.clear()
        CoverColorCache.clear()
        // 持久化的取色结果同样出自被重写的封面：一并作废，避免冷启动仍按旧色调铺底；
        // 新色调由显示端或切歌预取重取后重新落盘
        onInvalidatePersistedColors()
        withContext(NonCancellable) {
            // 落盘封面同样是旧图：一并作废，避免冷启动拿旧封面顶出（新封面由下次切歌重新落盘）
            context()?.let { context ->
                CurrentCoverCache.clear(context)
                LargeCoverStore.clear(context)
            }
        }
        coverRevision++
    }
}
