package com.yichao.evilgodxu.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

// 歌词与标题等文本的水平对齐。START 跟随布局方向，阿拉伯语等 RTL 语言下即靠右，
// 不按「左」命名以免把方向写死
enum class LyricAlignment { CENTER, START }

// 歌词排版默认值与可调范围
object LyricLayoutDefaults {
    const val HOME_PORTRAIT_FONT_SIZE_SP = 18
    const val LANDSCAPE_FONT_SIZE_SP = 18
    const val HOME_PORTRAIT_VISIBLE_LINES = 9
    const val LANDSCAPE_VISIBLE_LINES = 11
    const val LANDSCAPE_3D_INTENSITY = 1f
    // 居中是歌词的默认版式：整行随唱随亮，视觉重心落在屏幕中线
    val HOME_PORTRAIT_ALIGNMENT = LyricAlignment.CENTER
    const val FONT_SIZE_MIN_SP = 12
    const val FONT_SIZE_MAX_SP = 24
    // 奇数行预设：当前行居中，上下各 (n-1)/2 行；首页竖屏 3/5/7/9/11，横屏 7/9/11
    val HOME_PORTRAIT_LINE_PRESETS = listOf(3, 5, 7, 9, 11)
    val LANDSCAPE_LINE_PRESETS = listOf(7, 9, 11)
    const val THREE_D_MIN = 0f
    const val THREE_D_MAX = 2f
    const val THREE_D_STEP = 0.25f
    // 歌词行间距（dp）：相邻歌词行之间的纵向留白，两场景共用同一默认值与范围。
    // 默认值取行距与紧凑度的折中——行内上下内边距只撑开相邻行字形，行间留白靠它拉开
    const val LINE_SPACING_DEFAULT_DP = 10
    const val LINE_SPACING_MIN_DP = 0
    const val LINE_SPACING_MAX_DP = 18
    const val LINE_SPACING_STEP_DP = 1

    // 首次安装、旧版本升级以及读取前的首帧初值都取自这一份，避免各处各写一套默认值
    val HOME_PORTRAIT_LAYOUT = LyricLayoutParams(
        fontSizeSp = HOME_PORTRAIT_FONT_SIZE_SP,
        visibleLines = HOME_PORTRAIT_VISIBLE_LINES,
        lineSpacingDp = LINE_SPACING_DEFAULT_DP,
        alignment = HOME_PORTRAIT_ALIGNMENT,
    )
}

// 静态多行歌词排版参数（首页竖屏）；对齐方式同时作用于歌词与标题、艺术家名称
data class LyricLayoutParams(
    val fontSizeSp: Int,
    val visibleLines: Int,
    val lineSpacingDp: Int,
    val alignment: LyricAlignment,
)

// 首页横屏歌词排版参数：额外携带 3D 立体滚动强度
data class LandscapeLyricLayoutParams(
    val fontSizeSp: Int,
    val visibleLines: Int,
    val lineSpacingDp: Int,
    val threeDIntensity: Float,
)

private val homePortraitFontSizeKey = intPreferencesKey("lyric_font_size_home_portrait")
private val homePortraitLinesKey = intPreferencesKey("lyric_lines_home_portrait")
private val homePortraitLineSpacingKey = intPreferencesKey("lyric_line_spacing_home_portrait")
private val homePortraitAlignmentKey = stringPreferencesKey("lyric_alignment_home_portrait")
private val landscapeFontSizeKey = intPreferencesKey("lyric_font_size_landscape")
private val landscapeLinesKey = intPreferencesKey("lyric_lines_landscape")
private val landscapeLineSpacingKey = intPreferencesKey("lyric_line_spacing_landscape")
private val landscape3dKey = floatPreferencesKey("lyric_3d_intensity_landscape")

fun Context.homePortraitLyricLayoutFlow(): Flow<LyricLayoutParams> =
    settingsDataStore.data.map { preferences ->
        LyricLayoutParams(
            fontSizeSp = preferences[homePortraitFontSizeKey] ?: LyricLayoutDefaults.HOME_PORTRAIT_FONT_SIZE_SP,
            visibleLines = preferences[homePortraitLinesKey] ?: LyricLayoutDefaults.HOME_PORTRAIT_VISIBLE_LINES,
            lineSpacingDp = preferences[homePortraitLineSpacingKey] ?: LyricLayoutDefaults.LINE_SPACING_DEFAULT_DP,
            alignment = preferences[homePortraitAlignmentKey].toLyricAlignment(),
        )
    }

fun Context.landscapeLyricLayoutFlow(): Flow<LandscapeLyricLayoutParams> =
    settingsDataStore.data.map { preferences ->
        LandscapeLyricLayoutParams(
            fontSizeSp = preferences[landscapeFontSizeKey] ?: LyricLayoutDefaults.LANDSCAPE_FONT_SIZE_SP,
            visibleLines = preferences[landscapeLinesKey] ?: LyricLayoutDefaults.LANDSCAPE_VISIBLE_LINES,
            lineSpacingDp = preferences[landscapeLineSpacingKey] ?: LyricLayoutDefaults.LINE_SPACING_DEFAULT_DP,
            threeDIntensity = preferences[landscape3dKey] ?: LyricLayoutDefaults.LANDSCAPE_3D_INTENSITY,
        )
    }

suspend fun Context.saveHomePortraitLyricLayout(
    fontSizeSp: Int,
    visibleLines: Int,
    lineSpacingDp: Int,
    alignment: LyricAlignment,
) = withContext(Dispatchers.IO) {
    settingsDataStore.edit { preferences ->
        preferences[homePortraitFontSizeKey] = fontSizeSp.coerceIn(
            LyricLayoutDefaults.FONT_SIZE_MIN_SP,
            LyricLayoutDefaults.FONT_SIZE_MAX_SP,
        )
        preferences[homePortraitLinesKey] = nearestOddPreset(
            visibleLines, LyricLayoutDefaults.HOME_PORTRAIT_LINE_PRESETS,
        )
        preferences[homePortraitLineSpacingKey] = snapLineSpacing(lineSpacingDp)
        preferences[homePortraitAlignmentKey] = alignment.name
    }
}

suspend fun Context.saveLandscapeLyricLayout(
    fontSizeSp: Int,
    visibleLines: Int,
    lineSpacingDp: Int,
    threeDIntensity: Float,
) = withContext(Dispatchers.IO) {
    settingsDataStore.edit { preferences ->
        preferences[landscapeFontSizeKey] = fontSizeSp.coerceIn(
            LyricLayoutDefaults.FONT_SIZE_MIN_SP,
            LyricLayoutDefaults.FONT_SIZE_MAX_SP,
        )
        preferences[landscapeLinesKey] = nearestOddPreset(
            visibleLines, LyricLayoutDefaults.LANDSCAPE_LINE_PRESETS,
        )
        preferences[landscapeLineSpacingKey] = snapLineSpacing(lineSpacingDp)
        preferences[landscape3dKey] = (threeDIntensity / LyricLayoutDefaults.THREE_D_STEP)
            .roundToInt()
            .coerceIn(
                (LyricLayoutDefaults.THREE_D_MIN / LyricLayoutDefaults.THREE_D_STEP).toInt(),
                (LyricLayoutDefaults.THREE_D_MAX / LyricLayoutDefaults.THREE_D_STEP).toInt(),
            ) * LyricLayoutDefaults.THREE_D_STEP
    }
}

private fun nearestOddPreset(value: Int, presets: List<Int>): Int =
    presets.minByOrNull { kotlin.math.abs(it - value) } ?: value

// 键缺失或取值已不在枚举内（跨版本降级、数据损坏）时回落默认对齐，避免整份排版读取中断
private fun String?.toLyricAlignment(): LyricAlignment =
    LyricAlignment.entries.firstOrNull { it.name == this } ?: LyricLayoutDefaults.HOME_PORTRAIT_ALIGNMENT

// 避免越界写入或落成非整步值
private fun snapLineSpacing(value: Int): Int {
    val min = LyricLayoutDefaults.LINE_SPACING_MIN_DP
    val max = LyricLayoutDefaults.LINE_SPACING_MAX_DP
    val step = LyricLayoutDefaults.LINE_SPACING_STEP_DP
    val clamped = value.coerceIn(min, max)
    return (((clamped - min) / step) * step + min).coerceIn(min, max)
}
