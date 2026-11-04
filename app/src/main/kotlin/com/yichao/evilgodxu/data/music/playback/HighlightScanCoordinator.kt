package com.yichao.evilgodxu.data.music.playback

import android.content.Context
import com.yichao.evilgodxu.data.music.highlight.HighlightScanner
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.model.PlayMode
import com.yichao.evilgodxu.log.CrashLogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 心动模式的片段扫描：全库副歌定位、落盘与曲库变化后的补扫。
 *
 * 定位为纯歌词分析（不解码音频），与当前是否处于心动模式无关；扫描完成后若正处该模式，
 * 把新片段补进已装载队列。
 */
internal class HighlightScanCoordinator(
    private val scope: () -> CoroutineScope,
    private val context: () -> Context?,
    private val playMode: () -> PlayMode,
    private val libraryTracks: () -> List<MusicTrack>,
    private val onClipsUpdated: () -> Unit,
) {

    // 全库片段扫描任务：同一时刻只跑一个，重复触发直接返回
    private var scanJob: Job? = null

    // 上次补扫所依据的曲库签名：批量下载会逐首触发曲库变更，据此去重，不为每首各扫一遍
    private var scannedLibrary: Set<Long> = emptySet()

    /** 启动或复用一次扫描；只处理未扫过或歌词已变的曲目，重复调用无代价。 */
    fun ensureScan() {
        if (scanJob?.isActive == true) return
        val context = context() ?: return
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
                CrashLogManager.logException("HighlightScanCoordinator", "扫描副歌片段失败", e)
            }
        }
    }

    // 曲库变化后补扫：新入库的歌还没有副歌片段
    fun onLibraryChanged() {
        val signature = libraryTracks().mapTo(HashSet()) { it.id }
        if (signature == scannedLibrary) return
        scannedLibrary = signature
        ensureScan()
    }
}
