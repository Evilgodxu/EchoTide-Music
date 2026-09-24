package com.yichao.evilgodxu.screens.spectrum

import com.yichao.evilgodxu.data.music.analysis.Spectrogram

// 频谱分析页状态：分析在页面创建后立即开始并持续推进。
// 加载结束而结果仍为空，即该曲目不可分析（音频不可解码或源文件已不可访问）
data class SpectrumUiState(
    val title: String = "",
    val durationMs: Long = 0L,
    val analyzing: Boolean = true,
    val progress: Float = 0f,
    val spectrogram: Spectrogram? = null,
)
