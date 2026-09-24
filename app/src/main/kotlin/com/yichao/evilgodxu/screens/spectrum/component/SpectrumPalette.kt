package com.yichao.evilgodxu.screens.spectrum.component

// 强度色板自低到高：深蓝 → 蓝 → 青 → 绿 → 黄 → 橙 → 红。
// 频谱图取色与色标条共用同一组颜色，使条上任意高度取到的颜色即图中同一强度的颜色
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

// 色板档数：强度到颜色的映射逐像素发生，展开为查找表避免重复插值
private const val LUT_SIZE = 256

// 强度取色查找表：首档为最低强度、末档为满强度
private val COLOR_LUT = IntArray(LUT_SIZE) { index ->
    val position = index.toFloat() / (LUT_SIZE - 1) * (SPECTRUM_COLOR_STOPS.size - 1)
    val segment = position.toInt().coerceAtMost(SPECTRUM_COLOR_STOPS.size - 2)
    blendColor(
        SPECTRUM_COLOR_STOPS[segment],
        SPECTRUM_COLOR_STOPS[segment + 1],
        position - segment,
    )
}

// 按强度（0..1）取色，越界值收敛到色板两端
internal fun spectrumColor(intensity: Float): Int =
    COLOR_LUT[(intensity * (LUT_SIZE - 1)).toInt().coerceIn(0, LUT_SIZE - 1)]

// 按比例混合两个不透明色，结果保持不透明
private fun blendColor(from: Int, to: Int, fraction: Float): Int {
    val red = channelOf(from, 16) + ((channelOf(to, 16) - channelOf(from, 16)) * fraction).toInt()
    val green = channelOf(from, 8) + ((channelOf(to, 8) - channelOf(from, 8)) * fraction).toInt()
    val blue = channelOf(from, 0) + ((channelOf(to, 0) - channelOf(from, 0)) * fraction).toInt()
    return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
}

private fun channelOf(color: Int, shift: Int): Int = color shr shift and 0xFF
