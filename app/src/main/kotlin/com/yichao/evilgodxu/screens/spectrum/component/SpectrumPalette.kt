package com.yichao.evilgodxu.screens.spectrum.component

// 强度色板：深蓝到红，图与色标共用
internal val SPECTRUM_COLOR_STOPS = intArrayOf(
    0xFF060621.toInt(),
    0xFF14275E.toInt(),
    0xFF1B62B5.toInt(),
    0xFF17A0C4.toInt(),
    0xFF1FB86A.toInt(),
    0xFF8BC72A.toInt(),
    0xFFF0C020.toInt(),
    0xFFF07A18.toInt(),
    0xFFE02818.toInt(),
)

// 查找表 256 档，避免重复插值
private const val LUT_SIZE = 256

// 取色查找表：首低末满
private val COLOR_LUT = IntArray(LUT_SIZE) { index ->
    val position = index.toFloat() / (LUT_SIZE - 1) * (SPECTRUM_COLOR_STOPS.size - 1)
    val segment = position.toInt().coerceAtMost(SPECTRUM_COLOR_STOPS.size - 2)
    blendColor(
        SPECTRUM_COLOR_STOPS[segment],
        SPECTRUM_COLOR_STOPS[segment + 1],
        position - segment,
    )
}

// 0..1 取色，越界收敛
internal fun spectrumColor(intensity: Float): Int =
    COLOR_LUT[(intensity * (LUT_SIZE - 1)).toInt().coerceIn(0, LUT_SIZE - 1)]

// 混合两色，保持不透明
private fun blendColor(from: Int, to: Int, fraction: Float): Int {
    val red = channelOf(from, 16) + ((channelOf(to, 16) - channelOf(from, 16)) * fraction).toInt()
    val green = channelOf(from, 8) + ((channelOf(to, 8) - channelOf(from, 8)) * fraction).toInt()
    val blue = channelOf(from, 0) + ((channelOf(to, 0) - channelOf(from, 0)) * fraction).toInt()
    return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
}

private fun channelOf(color: Int, shift: Int): Int = color shr shift and 0xFF
