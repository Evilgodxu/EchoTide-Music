package com.yichao.evilgodxu.data.music.analysis

// 时频强度矩阵的动态范围：values 的 0 与 1 分别对应 −DYNAMIC_RANGE_DB 与峰值功率 0dB。
// 渲染端据此标注色标刻度，与解码端共用同一常量避免两处口径漂移
const val SPECTROGRAM_DYNAMIC_RANGE_DB = 120f

// 时频强度矩阵：整首音频的短时分析结果，供频谱图渲染。
// values 以时间为主序按下标 frame * rows + row 存放，row 沿频率自低到高，取值 0..1。
// 不带 equals/hashCode：矩阵规模大且只作只读传递，值比较没有意义
class Spectrogram(
    val values: FloatArray,
    val columns: Int,
    val rows: Int,
    // 解码采样率：频率轴上限即其奈奎斯特频率，供刻度标注
    val sampleRate: Int,
)
