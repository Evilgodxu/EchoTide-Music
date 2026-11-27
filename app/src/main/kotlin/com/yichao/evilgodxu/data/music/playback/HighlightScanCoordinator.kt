package com.yichao.evilgodxu.data.music.playback

import android.content.Context
import com.yichao.evilgodxu.data.music.highlight.HighlightScanner
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.model.PlayMode
import com.yichao.evilgodxu.log.CrashLogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 心动片段扫描：定位副歌、落盘、曲库变化补扫；只分析歌词 */
internal class HighlightScanCoordinator(
    private val scope: () -> CoroutineScope,
    private val context: () -> Context?,
    private val playMode: () -> PlayMode,
    private val libraryTracks: () -> List<MusicTrack>,
    private val onClipsUpdated: () -> Unit,
) {

    // 全库片段扫描任务：同一时刻只跑一个，重复触发直接返回
    private var scanJob: Job? = null

    // 补扫依据的曲库签名：批量下载逐首触发变更，据此去重
    private var scannedLibrary: Set<Long> = emptySet()

    /** 启动或复用扫描；重复调用无代价 */
    fun ensureScan() {
        if (scanJob?.isActive == true) return
        val context = context() ?: return
        val tracks = libraryTracks()
        if (tracks.isEmpty()) return
        scanJob = scope().launch {
            try {
                val updated = HighlightScanner.scan(context, tracks)
                // 表变了就补进已装载队列
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

    // 曲库变化后补扫新曲
    fun onLibraryChanged() {
        val signature = libraryTracks().mapTo(HashSet()) { it.id }
        if (signature == scannedLibrary) return
        scannedLibrary = signature
        ensureScan()
    }
}
