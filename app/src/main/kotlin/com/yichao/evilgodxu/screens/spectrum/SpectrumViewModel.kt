package com.yichao.evilgodxu.screens.spectrum

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yichao.evilgodxu.data.music.analysis.SpectrogramDecoder
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

// 频谱分析页状态持有者：进入页面即对目标曲目做完整频谱分析，
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
            // 不可分析：结束加载态，页面据时频矩阵为空展示不可分析占位
            _uiState.update { it.copy(analyzing = false) }
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

    // 全曲分析：把整曲解码为可直接渲染的时频矩阵。
    // 时频矩阵为空说明音频不可解码，此时页面按不可分析处理
    private fun analyse(track: MusicTrack) {
        viewModelScope.launch {
            val spectrogram = SpectrogramDecoder.decode(track) { progress ->
                _uiState.update { it.copy(progress = progress) }
            }
            _uiState.update { it.copy(analyzing = false, spectrogram = spectrogram) }
        }
    }
}
