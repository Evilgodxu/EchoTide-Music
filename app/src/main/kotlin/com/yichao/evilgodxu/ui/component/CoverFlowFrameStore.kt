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
 * 代现算缓存：切歌起播即算好帧，显示端直接命中。
 * 只消费内存中的封面与取色，不触发取图，取不到则作罢。
 * 按曲目驻留最近 3 首，封面版本号进键。
 */
internal object CoverFlowFrameStore {

    // 驻留 3 首，帧约占视口 1/256，占用可忽略
    private const val RESIDENT_CAPACITY = 3

    /**
     * 起算延迟 500ms，压掉连点切歌并等封面准备落定
     */
    private const val PREPARE_DELAY_MS = 500L

    private data class Key(val audioUri: String, val coverRevision: Int)

    private val resident = object : LruCache<Key, ImageBitmap>(RESIDENT_CAPACITY) {}

    // 显示尺寸由显示端上报，两处按同尺寸渲染
    @Volatile
    private var viewport = IntSize.Zero

    // 观察与现算在独立作用域，后台照常运行
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false

    /** 取已备好的帧，未命中返回 null */
    fun peek(audioUri: String?, coverRevision: Int): ImageBitmap? =
        audioUri?.let { resident.get(Key(it, coverRevision)) }

    /** 更新显示尺寸 */
    fun setViewport(size: IntSize) {
        viewport = size
    }

    /**
     * 观察切歌并代为现算，首页首次组合调用一次
     */
    fun start(state: MusicPlaybackState) {
        if (started) return
        started = true
        scope.launch {
            snapshotFlow { state.currentTrack to state.coverRevision }
                // 换曲即弃掉上一轮等待与现算
                .collectLatest { (track, revision) -> track?.let { prepare(it, revision) } }
        }
    }

    // 尺寸、取图与取色口径与显示端一致
    private suspend fun prepare(track: MusicTrack, coverRevision: Int) {
        delay(PREPARE_DELAY_MS)
        val size = viewport
        if (size.width <= 0 || size.height <= 0) return
        // 按显示端同一顺序取内存封面
        val cover = MusicCoverLoader.cachedThumbnail(track, COVER_BACKGROUND_SAMPLE_SIZE)
            ?: CurrentCoverCache.peek(track.audioUri)
            ?: return
        // 优先命中预取取色，未命中现算并回填
        val colors = CoverColorCache.get(track.audioUri)
            ?: extractCoverBackgroundColors(cover)?.also { CoverColorCache.put(track.audioUri, it) }
            ?: return
        // 相位取零点，与显示端接管后同相位
        val frame = withContext(Dispatchers.Default) {
            renderCoverFlowFrame(cover, size, colors.representative, timeMs = 0L)
        }
        resident.put(Key(track.audioUri, coverRevision), frame)
    }
}
