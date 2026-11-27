package com.yichao.evilgodxu.screens.spectrum

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yichao.evilgodxu.data.music.analysis.SpectrogramDecoder
import com.yichao.evilgodxu.data.music.analysis.TrackAudioInfoReader
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 进入即全曲分析，随作用域取消
class SpectrumViewModel(
    application: Application,
    playbackState: MusicPlaybackState,
    trackId: Long,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(SpectrumUiState())
    val uiState: StateFlow<SpectrumUiState> = _uiState.asStateFlow()

    init {
        val track = playbackState.playlist.firstOrNull { it.id == trackId }
            ?: playbackState.libraryTracks.firstOrNull { it.id == trackId }
        if (track == null || !track.isLocalAudioSource) {
            // 不可分析：结束加载态
            _uiState.update { it.copy(analyzing = false) }
        } else {
            _uiState.update {
                it.copy(title = track.title, artist = track.artist, durationMs = track.duration)
            }
            loadTrackInfo(track)
            analyse(track)
        }
    }

    // 读容器头与元数据，与分析并行
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

    // 整曲解码成时频矩阵；空即不可分析
    private fun analyse(track: MusicTrack) {
        viewModelScope.launch {
            val spectrogram = withContext(Dispatchers.Default) {
                SpectrogramDecoder.decode(track) { progress ->
                    _uiState.update { it.copy(progress = progress) }
                }
            }
            _uiState.update { it.copy(analyzing = false, spectrogram = spectrogram) }
        }
    }
}
