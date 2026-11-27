package com.yichao.evilgodxu.screens.spectrum

import com.yichao.evilgodxu.data.music.analysis.Spectrogram
import com.yichao.evilgodxu.data.music.playback.AudioSignalPathFormat

// 加载结束仍无矩阵即不可分析
data class SpectrumUiState(
    val title: String = "",
    // 歌手名：仅导出图使用
    val artist: String = "",
    val durationMs: Long = 0L,
    val analyzing: Boolean = true,
    val progress: Float = 0f,
    val spectrogram: Spectrogram? = null,
    // 格式与体积直读文件元数据
    val signalFormat: AudioSignalPathFormat? = null,
    val sizeBytes: Long = 0L,
)
