package com.yichao.evilgodxu.data.music.playback

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.session.MediaController
import com.yichao.evilgodxu.data.music.highlight.HighlightScanner
import com.yichao.evilgodxu.data.music.highlight.HighlightStore
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.model.PlayMode
import com.yichao.evilgodxu.log.CrashLogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 心动模式的自有状态与策略：片段扫描、跳过无副歌曲目、退出缘由与音量斜坡。
 *
 * 模式切换本身（重建队列、同步播放器模式）由播放会话协调；本类只回答
 * 「进入该模式前要不要拦」「这一首该不该跳过」「扫描何时补」「片段首尾音量怎么走」。
 */
internal class HighlightModeController(
    private val scope: () -> CoroutineScope,
    private val context: () -> Context?,
    private val playMode: () -> PlayMode,
    private val currentTrack: () -> MusicTrack?,
    private val playlist: () -> List<MusicTrack>,
    private val currentIndex: () -> Int,
    private val libraryTracks: () -> List<MusicTrack>,
    private val mediaController: () -> MediaController?,
    private val nextIndex: () -> Int,
    private val playAt: (context: Context, index: Int) -> Unit,
    private val exitToNormal: () -> Unit,
    private val onClipsUpdated: () -> Unit,
    private val immersiveOutputActive: () -> Boolean,
) {

    private companion object {
        // 片段首尾的音量斜坡时长：淡入与淡出各占一段，靠降低音量掩掉硬切
        const val HIGHLIGHT_FADE_MS = 800L
    }

    // 心动模式自行退出的一次性原因，由界面消费后清空。退出有多种缘由（定位不到副歌、进入直出），
    // 界面据此说明模式为何自己变了 —— 否则用户会当成失灵
    var exitNotice by mutableStateOf<HighlightExitReason?>(null)

    // 连续跳过计数：整队列都无可定位副歌时防止回绕空转
    private var skipStreak = 0

    // 全库片段扫描任务：同一时刻只跑一个，重复触发直接返回
    private var scanJob: Job? = null

    // 上次补扫副歌片段所依据的曲库签名：批量下载会逐首触发曲库变更，据此去重，不为每首各扫一遍
    private var scannedLibrary: Set<Long> = emptySet()

    /** 播放模式变更：跳过计数只对当前这一段模式存续周期有意义。 */
    fun onModeChanged() {
        skipStreak = 0
    }

    /** 沉浸式输出期间不接受心动模式：界面据此说明缘由而不是静默不响应。 */
    fun notifyImmersiveBlocked() {
        exitNotice = HighlightExitReason.ImmersiveOutput
    }

    /**
     * USB 输出开关变化：一经启用即退出心动模式。
     *
     * 开关可以在曲中才被拨动，此时模式不能就这么留在片段播放上——片段首尾的音量淡入淡出在沉浸链路上
     * 无从实施，队列项也仍是裁过的那些。调用方须先写好开关状态再调本方法：判据读的是当前状态。
     * 成色回调来自播放线程与主线程两处，故统一切到主线程再改播放模式。
     */
    fun onImmersiveOutputChanged() {
        if (!immersiveOutputActive() || playMode() != PlayMode.Highlight) return
        scope().launch {
            if (playMode() != PlayMode.Highlight) return@launch
            exitNotice = HighlightExitReason.ImmersiveOutput
            exitToNormal()
        }
    }

    /**
     * 后台扫描全库副歌片段并落盘。
     *
     * 播放路径只查表：扫描在这里一次做完，切模式与切歌都不再解析，既不卡顿也不重复计算。
     * 只处理未扫过或歌词已变的曲目，重复调用无代价。启动恢复完成、曲库变化、启用心动模式三处触发。
     *
     * 定位为纯歌词分析（不解码音频），故扫描与当前模式无关：无论是否处于心动模式都只读歌词。
     */
    fun ensureScan(context: Context) {
        if (scanJob?.isActive == true) return
        val tracks = libraryTracks()
        if (tracks.isEmpty()) return
        scanJob = scope().launch {
            try {
                val updated = HighlightScanner.scan(context, tracks)
                // 表变了就把新片段补进已装载的队列：队列可能先于扫描装载，那一版还是整曲项
                if (updated && playMode() == PlayMode.Highlight) {
                    onClipsUpdated()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                CrashLogManager.logException("HighlightModeController", "扫描副歌片段失败", e)
            }
        }
    }

    /**
     * 本地曲库变化（缓存 / 下载入库）后补扫。
     *
     * 新入库的歌还没有副歌片段，补一次扫描（只处理指纹过期的曲目）。
     */
    fun onLibraryChanged() {
        val signature = libraryTracks().mapTo(HashSet()) { it.id }
        if (signature == scannedLibrary) return
        scannedLibrary = signature
        context()?.let(::ensureScan)
    }

    /**
     * 心动模式下曲目落定后的收尾：不可定位副歌的曲目直接跳过。
     *
     * 跳过带回绕保护：整队列都定位不到时（纯音乐歌单、歌词全无），连续跳过会绕回原点空转，
     * 故连续跳过数超过队列长度即退出该模式交回普通播放，并置一次性提示供界面说明原因。
     *
     * 只有「已扫描、有歌词、且判定无副歌」才跳过。歌词尚未就绪不等于没有副歌 ——
     * 据此跳过会在冷启动时把整库都跳过，故未就绪时照常整曲播放，等扫描补齐后即是片段。
     */
    fun onTrackSettled() {
        if (playMode() != PlayMode.Highlight) {
            skipStreak = 0
            return
        }
        val context = context() ?: return
        val track = currentTrack() ?: return
        if (!isKnownHighlightMiss(track)) {
            skipStreak = 0
            return
        }
        if (skipStreak >= playlist().size) {
            skipStreak = 0
            exitNotice = HighlightExitReason.NoChorus
            exitToNormal()
            return
        }
        skipStreak++
        val next = nextIndex()
        if (next in playlist().indices && next != currentIndex()) {
            playAt(context, next)
        }
    }

    // 当前播放项是否为心动模式的裁剪片段：只有它有「首尾」，因而才需要音量淡入淡出
    fun isClipActive(): Boolean =
        playMode() == PlayMode.Highlight &&
            currentTrack()?.let { HighlightStore.segmentOf(it.id) } != null

    /**
     * 把片段首尾的音量斜坡写进播放器音量。
     *
     * 片段是靠裁剪切开的，首尾都是硬切；这里在片段开头把音量由 0 升到 1、在结尾前由 1 降到 0，
     * 用降低音量做出淡入淡出，进出不再是突兀的一下。
     *
     * 位置与时长都取控制器对**当前项**的回报：裁剪生效后它们本就以片段为原点，正是斜坡要的坐标系。
     * 仅对确有片段的曲目生效 —— 心动模式下没有片段的曲目照常整曲播放，不该被从头淡入到尾。
     */
    fun syncFadeVolume() {
        val controller = mediaController() ?: return
        val target = if (isClipActive()) {
            val duration = controller.duration
            val position = controller.currentPosition
            if (duration <= 0L || position < 0L) {
                1f
            } else {
                val fadeIn = position.toFloat() / HIGHLIGHT_FADE_MS
                val fadeOut = (duration - position).toFloat() / HIGHLIGHT_FADE_MS
                minOf(fadeIn, fadeOut).coerceIn(0f, 1f)
            }
        } else {
            1f
        }
        if (controller.volume != target) controller.volume = target
    }
}
