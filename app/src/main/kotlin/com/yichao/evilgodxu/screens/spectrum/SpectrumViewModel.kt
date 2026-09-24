package com.yichao.evilgodxu.screens.spectrum

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yichao.evilgodxu.data.music.analysis.AiMusicAnalyzer
import com.yichao.evilgodxu.data.music.analysis.FakeLosslessAnalyzer
import com.yichao.evilgodxu.data.music.analysis.SpectrogramDecoder
import com.yichao.evilgodxu.data.music.analysis.TrackAudioInfoReader
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.panel.MusicPanelStateHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 频谱分析页状态持有者：进入页面即对目标曲目做全曲时频分析并跑信号判定，
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
            _uiState.update { it.copy(title = track.title, durationMs = track.duration) }
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

    // 全曲时频分析与信号判定并行推进：音质异常与 AI 合成各自复用单曲判定入口，
    // 结论与曲库分析同源同缓存，不会出现两处口径不一致。
    // 时频矩阵为空说明音频不可解码，此时两路判定不予采信，统一置为不适用
    private fun analyse(track: MusicTrack) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val spectrogramDeferred = async(Dispatchers.Default) {
                SpectrogramDecoder.decode(track) { progress ->
                    _uiState.update { it.copy(progress = progress) }
                }
            }
            // 音质异常仅校验 FLAC，非候选不发起点，结论保持不适用
            val fakeDeferred = if (FakeLosslessAnalyzer.isFlacCandidate(track)) {
                async(Dispatchers.IO) {
                    FakeLosslessAnalyzer.isSuspectedFakeLossless(context, track)
                }
            } else {
                null
            }
            val aiDeferred = if (AiMusicAnalyzer.isDecodableCandidate(track)) {
                async(Dispatchers.IO) { AiMusicAnalyzer.isSuspectedAiMusic(context, track) }
            } else {
                null
            }
            val spectrogram = spectrogramDeferred.await()
            val fake = fakeDeferred?.await()
            val ai = aiDeferred?.await()
            _uiState.update {
                it.copy(
                    analyzing = false,
                    spectrogram = spectrogram,
                    analysis = if (spectrogram == null) {
                        SpectrumAnalysis(checking = false)
                    } else {
                        SpectrumAnalysis(checking = false, fakeLossless = fake, aiMusic = ai)
                    },
                )
            }
        }
    }
}
