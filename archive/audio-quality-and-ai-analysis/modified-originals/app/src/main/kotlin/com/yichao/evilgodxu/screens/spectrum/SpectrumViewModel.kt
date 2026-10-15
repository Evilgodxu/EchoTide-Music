package com.yichao.evilgodxu.screens.spectrum

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yichao.evilgodxu.data.music.analysis.FullSpectrumAnalyzer
import com.yichao.evilgodxu.data.music.analysis.TrackAudioInfoReader
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.panel.MusicPanelStateHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 频谱分析页状态持有者：进入页面即对目标曲目做完整频谱分析并跑信号判定，
// 离开页面时随作用域取消
class SpectrumViewModel(
    application: Application,
    stateHolder: MusicPanelStateHolder,
    trackId: Long,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(SpectrumUiState())
    val uiState: StateFlow<SpectrumUiState> = _uiState.asStateFlow()

    init {
        val playbackState = stateHolder.state
        // 曲目可能经播放队列或曲库浏览列表进入，两处都查一遍
        val track = playbackState.playlist.firstOrNull { it.id == trackId }
            ?: playbackState.libraryTracks.firstOrNull { it.id == trackId }
        if (track == null || !track.isLocalAudioSource) {
            // 不可分析：判定结论一并置为不适用，避免「未检出」被误读为「已确认正常」
            _uiState.update {
                it.copy(analyzing = false, analysis = SpectrumAnalysis(checking = false))
            }
        } else {
            _uiState.update {
                it.copy(title = track.title, artist = track.artist, durationMs = track.duration)
            }
            loadTrackInfo(track)
            analyse(track)
        }
    }

    // 源文件格式参数与体积：读容器头与媒体元数据，与频谱分析并行，不阻塞主线程
    private fun loadTrackInfo(track: MusicTrack) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val (format, size) = withContext(Dispatchers.IO) {
                TrackAudioInfoReader.readIdleFormat(context, track) to
                    TrackAudioInfoReader.readFileSize(context, track)
            }
            _uiState.update { it.copy(signalFormat = format, sizeBytes = size ?: 0L) }
        }
    }

    // 全曲分析：完整频谱与两路判定出自同一次解码，结论写入两路共用判定缓存并锁定该曲，
    // 此后曲库分析的分段快速采样不再改写该结论（见 FullSpectrumAnalyzer 与 FullAnalysisLock）。
    // 时频矩阵为空说明音频不可解码，此时两路判定不予采信，统一置为不适用
    private fun analyse(track: MusicTrack) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val verdict = FullSpectrumAnalyzer.analyze(context, track) { progress ->
                _uiState.update { it.copy(progress = progress) }
            }
            _uiState.update {
                it.copy(
                    analyzing = false,
                    spectrogram = verdict.spectrogram,
                    analysis = if (verdict.spectrogram == null) {
                        SpectrumAnalysis(checking = false)
                    } else {
                        SpectrumAnalysis(
                            checking = false,
                            fakeLossless = verdict.fakeLossless,
                            aiMusic = verdict.aiMusic,
                        )
                    },
                )
            }
        }
    }
}
