package com.yichao.evilgodxu.screens.spectrum

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yichao.evilgodxu.data.music.analysis.SpectrogramDecoder
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.panel.MusicPanelStateHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 频谱分析页状态持有者：进入页面即对目标曲目做全曲时频分析，离开页面时随作用域取消
class SpectrumViewModel(
    stateHolder: MusicPanelStateHolder,
    trackId: Long,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SpectrumUiState())
    val uiState: StateFlow<SpectrumUiState> = _uiState.asStateFlow()

    init {
        val playbackState = stateHolder.state
        // 曲目可能经播放队列或曲库浏览列表进入，两处都查一遍
        val track = playbackState.playlist.firstOrNull { it.id == trackId }
            ?: playbackState.libraryTracks.firstOrNull { it.id == trackId }
        if (track == null || !track.isLocalAudioSource) {
            _uiState.update { it.copy(analyzing = false) }
        } else {
            _uiState.update { it.copy(title = track.title, durationMs = track.duration) }
            analyse(track)
        }
    }

    // 全曲解码与变换是纯计算与阻塞 IO，整体移出主线程；进度经状态流驱动进度指示
    private fun analyse(track: MusicTrack) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                SpectrogramDecoder.decode(track) { progress ->
                    _uiState.update { it.copy(progress = progress) }
                }
            }
            _uiState.update { it.copy(analyzing = false, spectrogram = result) }
        }
    }
}
