package com.yichao.evilgodxu.screens.spectrum

import com.yichao.evilgodxu.data.music.analysis.Spectrogram
import com.yichao.evilgodxu.data.music.playback.AudioSignalPathFormat

// 曲目信号判定结论：null 表示该维度不适用——非 FLAC 不做音质异常判定，音频不可分析时两路都不适用
data class SpectrumAnalysis(
    val checking: Boolean = true,
    val fakeLossless: Boolean? = null,
    val aiMusic: Boolean? = null,
)

// 是否值得陈列判定结论：曲目无从分析时两路都不适用，底部整块留白比列两行「不适用」更有信息量
fun SpectrumAnalysis.hasVerdict(): Boolean =
    checking || fakeLossless != null || aiMusic != null

// 频谱分析页状态：分析与参数读取在页面创建后立即开始并持续推进。
// 加载结束而时频矩阵仍为空，即该曲目不可分析（音频不可解码或源文件已不可访问）
data class SpectrumUiState(
    val title: String = "",
    // 歌手名：导出图底行与曲名同显，界面标题栏不用
    val artist: String = "",
    val durationMs: Long = 0L,
    val analyzing: Boolean = true,
    val progress: Float = 0f,
    val spectrogram: Spectrogram? = null,
    // 源文件格式参数与体积：直接读文件元数据，不依赖该曲目是否正在播放
    val signalFormat: AudioSignalPathFormat? = null,
    val sizeBytes: Long = 0L,
    val analysis: SpectrumAnalysis = SpectrumAnalysis(),
)
