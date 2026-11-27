package com.yichao.evilgodxu.data.music.analysis

// 动态范围 120dB，0..1 映射 -120..0dB
const val SPECTROGRAM_DYNAMIC_RANGE_DB = 120f

// 时频强度矩阵，值按 frame * rows + row 存
// 第 row 行对应 row/rows 奈奎斯特
// 不带 equals，规模大且只读
class Spectrogram(
    val values: FloatArray,
    val columns: Int,
    val rows: Int,
    // 解码采样率，频率轴上限为其奈奎斯特
    val sampleRate: Int,
)
