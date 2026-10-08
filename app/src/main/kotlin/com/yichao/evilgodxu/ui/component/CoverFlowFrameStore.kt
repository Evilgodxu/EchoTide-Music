package com.yichao.evilgodxu.ui.component

import android.util.LruCache
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntSize
import com.yichao.evilgodxu.data.music.metadata.CoverColorCache
import com.yichao.evilgodxu.data.music.metadata.CurrentCoverCache
import com.yichao.evilgodxu.data.music.metadata.MusicCoverLoader
import com.yichao.evilgodxu.data.music.metadata.extractCoverBackgroundColors
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 背景流动帧的代现算缓存：切歌起播时就把当前曲目的帧现算好，显示端随后直接命中。
 *
 * 帧原本只由显示端在组合里现算，而组合在应用不可见期间停摆——后台切歌要等回前台才起步，
 * 首帧只能先铺上一首的那一帧，成了一次可见的跳变。此处把现算挪到切歌起播的时刻：
 * 观察的是快照，快照的写入、应用与通知都不依赖帧时钟，应用不可见期间照常送达（见 [start]）。
 * 显示端不重组却需要帧的场合都由此兜住：退到后台的切歌、重新进入页面、配置变更后的重建。
 *
 * 只消费显示端与切歌准备已经备在内存里的产物，自身不触发任何取图：
 * 封面按显示端首帧的同一顺序取（见 [prepare]），取色优先命中切歌时预取的结果，
 * 于是这里算出的帧与显示端随后现算出的帧同源，连相位都同源（都从本曲起播的零点算起），
 * 两者可以互换，看不出接缝。取不到就作罢，由显示端自己现算——代现算只是抢时间，不承担兜底。
 *
 * 与背景取色、大封面等同为封面衍生的展示资产，口径也一致：按曲目驻留最近几首。
 * 封面版本号进键：封面被重写后旧帧不再成立，换代后旧键自然取不到，无需另行作废。
 */
internal object CoverFlowFrameStore {

    // 驻留条数：与背景取色缓存同一批（当前曲及其前后各一首）。帧只有 1/16 视口的像素量，占用可忽略
    private const val RESIDENT_CAPACITY = 3

    /**
     * 起算延迟：压掉连点切歌，并等切歌那轮封面准备落定——落定后封面已在内存里，
     * 本处的代现算才是纯内存读取加一次叠画。取半秒：远短于封面预取的稳定期，回前台前有充裕余量
     */
    private const val PREPARE_DELAY_MS = 500L

    private data class Key(val audioUri: String, val coverRevision: Int)

    private val resident = object : LruCache<Key, ImageBitmap>(RESIDENT_CAPACITY) {}

    // 显示尺寸：由显示端上报。代现算按同一尺寸渲染，帧的构图才与显示端现算出的完全一致
    @Volatile
    private var viewport = IntSize.Zero

    // 背景流动开关：由显示端上报。它本就是开关的唯一观察者，关闭流动时帧根本不参与渲染
    @Volatile
    private var flowEnabled = false

    // 观察与现算都在本作用域内：进程级缓存，不随任何一处界面离场而中断。
    // 取主调度器，与播放状态自身的协程同源（见 MusicPlaybackState.playbackScope）：
    // 主队列在应用退到后台后照常运行，观察因此不会被调度器挡在门外
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false

    /** 同步取已备好的帧；未命中返回 null，由显示端自行现算 */
    fun peek(audioUri: String?, coverRevision: Int): ImageBitmap? =
        audioUri?.let { resident.get(Key(it, coverRevision)) }

    /** 显示尺寸变更：其后代现算按新尺寸渲染 */
    fun setViewport(size: IntSize) {
        viewport = size
    }

    /** 背景流动开关变更：关闭时停止代现算 */
    fun setFlowEnabled(enabled: Boolean) {
        flowEnabled = enabled
    }

    /**
     * 观察切歌并代为现算：由首页首次组合调用一次，重复调用无副作用。
     *
     * 观察落在本对象自己的作用域而非组合：应用不可见时组合与其协程一并停摆，
     * 观察若挂在组合上，后台切歌根本无从触发——这正是原本要等回前台才补算的缘由。
     */
    fun start(state: MusicPlaybackState) {
        if (started) return
        started = true
        scope.launch {
            snapshotFlow { state.currentTrack to state.coverRevision }
                // 换曲即弃掉上一轮的等待与现算：连点切歌时既不为已划走的曲目起算，也不续算
                .collectLatest { (track, revision) -> track?.let { prepare(it, revision) } }
        }
    }

    // 代现算一帧并驻留：尺寸、取图与取色的口径都与显示端一致，两处算出的帧才同源
    private suspend fun prepare(track: MusicTrack, coverRevision: Int) {
        delay(PREPARE_DELAY_MS)
        val size = viewport
        if (size.width <= 0 || size.height <= 0) return
        if (!flowEnabled) return
        // 封面按显示端首帧的同一顺序取内存里那一张：两处落在同一张图上，帧才与显示端现算的完全一致
        val cover = MusicCoverLoader.cachedThumbnail(track, COVER_BACKGROUND_SAMPLE_SIZE)
            ?: CurrentCoverCache.peek(track.audioUri)
            ?: return
        // 取色同显示端：优先命中切歌时预取的结果，未命中才由这张封面现算并回填
        val colors = CoverColorCache.get(track.audioUri)
            ?: extractCoverBackgroundColors(cover)?.also { CoverColorCache.put(track.audioUri, it) }
            ?: return
        // 相位取零点：本曲目起播即零点，显示端接管后也从这一零点接着走（见流动时间轴）
        val frame = withContext(Dispatchers.Default) {
            renderCoverFlowFrame(cover, size, colors.representative, timeMs = 0L)
        }
        resident.put(Key(track.audioUri, coverRevision), frame)
    }
}
