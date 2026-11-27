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

// 对齐方式；START 随布局方向，RTL 下即靠右
enum class LyricAlignment { CENTER, START }

// 歌词排版默认值与可调范围
object LyricLayoutDefaults {
    const val HOME_PORTRAIT_FONT_SIZE_SP = 18
    const val LANDSCAPE_FONT_SIZE_SP = 18
    const val HOME_PORTRAIT_VISIBLE_LINES = 9
    const val LANDSCAPE_VISIBLE_LINES = 11
    const val LANDSCAPE_3D_INTENSITY = 1f
    // 默认居中，视觉重心落在中线
    val HOME_PORTRAIT_ALIGNMENT = LyricAlignment.CENTER
    const val FONT_SIZE_MIN_SP = 12
    const val FONT_SIZE_MAX_SP = 24
    // 奇数行预设，当前行居中，上下各半
    val HOME_PORTRAIT_LINE_PRESETS = listOf(3, 5, 7, 9, 11)
    val LANDSCAPE_LINE_PRESETS = listOf(7, 9, 11)
    const val THREE_D_MIN = 0f
    const val THREE_D_MAX = 2f
    const val THREE_D_STEP = 0.25f
    // 行间距 dp，两场景共用默认值与范围
    const val LINE_SPACING_DEFAULT_DP = 10
    const val LINE_SPACING_MIN_DP = 0
    const val LINE_SPACING_MAX_DP = 18
    const val LINE_SPACING_STEP_DP = 1

    // 渐隐以半行为档，可精确表示
    const val FADE_STEP_LINES = 0.5f
    // 渐隐长度（行），默认 1.5 可盖住溢出薄片
    const val FADE_LINES_DEFAULT = 1.5f
    const val FADE_LINES_MIN = 0.5f
    const val FADE_LINES_MAX = 3f
    // 渐隐留白行数，默认 0 保持版式
    const val FADE_INSET_LINES_DEFAULT = 0f
    const val FADE_INSET_LINES_MIN = 0f
    const val FADE_INSET_LINES_MAX = 2f

    // 首帧与升级初值统一取这两份默认
    val HOME_PORTRAIT_LAYOUT = LyricLayoutParams(
        fontSizeSp = HOME_PORTRAIT_FONT_SIZE_SP,
        visibleLines = HOME_PORTRAIT_VISIBLE_LINES,
        lineSpacingDp = LINE_SPACING_DEFAULT_DP,
        alignment = HOME_PORTRAIT_ALIGNMENT,
        fadeLines = FADE_LINES_DEFAULT,
        fadeInsetLines = FADE_INSET_LINES_DEFAULT,
    )
    val LANDSCAPE_LAYOUT = LandscapeLyricLayoutParams(
        fontSizeSp = LANDSCAPE_FONT_SIZE_SP,
        visibleLines = LANDSCAPE_VISIBLE_LINES,
        lineSpacingDp = LINE_SPACING_DEFAULT_DP,
        threeDIntensity = LANDSCAPE_3D_INTENSITY,
        fadeLines = FADE_LINES_DEFAULT,
        fadeInsetLines = FADE_INSET_LINES_DEFAULT,
    )
}

// 首页竖屏排版；对齐同时作用于标题与艺术家
data class LyricLayoutParams(
    val fontSizeSp: Int,
    val visibleLines: Int,
    val lineSpacingDp: Int,
    val alignment: LyricAlignment,
    val fadeLines: Float,
    val fadeInsetLines: Float,
)

// 首页横屏排版，额外含 3D 强度
data class LandscapeLyricLayoutParams(
    val fontSizeSp: Int,
    val visibleLines: Int,
    val lineSpacingDp: Int,
    val threeDIntensity: Float,
    val fadeLines: Float,
    val fadeInsetLines: Float,
)

private val homePortraitFontSizeKey = intPreferencesKey("lyric_font_size_home_portrait")
private val homePortraitLinesKey = intPreferencesKey("lyric_lines_home_portrait")
private val homePortraitLineSpacingKey = intPreferencesKey("lyric_line_spacing_home_portrait")
private val homePortraitAlignmentKey = stringPreferencesKey("lyric_alignment_home_portrait")
private val homePortraitFadeKey = floatPreferencesKey("lyric_fade_lines_home_portrait")
private val homePortraitFadeInsetKey = floatPreferencesKey("lyric_fade_inset_lines_home_portrait")
private val landscapeFontSizeKey = intPreferencesKey("lyric_font_size_landscape")
private val landscapeLinesKey = intPreferencesKey("lyric_lines_landscape")
private val landscapeLineSpacingKey = intPreferencesKey("lyric_line_spacing_landscape")
private val landscape3dKey = floatPreferencesKey("lyric_3d_intensity_landscape")
private val landscapeFadeKey = floatPreferencesKey("lyric_fade_lines_landscape")
private val landscapeFadeInsetKey = floatPreferencesKey("lyric_fade_inset_lines_landscape")

fun Context.homePortraitLyricLayoutFlow(): Flow<LyricLayoutParams> =
    settingsDataStore.data.map { preferences ->
        LyricLayoutParams(
            fontSizeSp = preferences[homePortraitFontSizeKey] ?: LyricLayoutDefaults.HOME_PORTRAIT_FONT_SIZE_SP,
            visibleLines = preferences[homePortraitLinesKey] ?: LyricLayoutDefaults.HOME_PORTRAIT_VISIBLE_LINES,
            lineSpacingDp = preferences[homePortraitLineSpacingKey] ?: LyricLayoutDefaults.LINE_SPACING_DEFAULT_DP,
            alignment = preferences[homePortraitAlignmentKey].toLyricAlignment(),
            fadeLines = snapHalfLines(
                preferences[homePortraitFadeKey] ?: LyricLayoutDefaults.FADE_LINES_DEFAULT,
                LyricLayoutDefaults.FADE_LINES_MIN,
                LyricLayoutDefaults.FADE_LINES_MAX,
            ),
            fadeInsetLines = snapHalfLines(
                preferences[homePortraitFadeInsetKey] ?: LyricLayoutDefaults.FADE_INSET_LINES_DEFAULT,
                LyricLayoutDefaults.FADE_INSET_LINES_MIN,
                LyricLayoutDefaults.FADE_INSET_LINES_MAX,
            ),
        )
    }

fun Context.landscapeLyricLayoutFlow(): Flow<LandscapeLyricLayoutParams> =
    settingsDataStore.data.map { preferences ->
        LandscapeLyricLayoutParams(
            fontSizeSp = preferences[landscapeFontSizeKey] ?: LyricLayoutDefaults.LANDSCAPE_FONT_SIZE_SP,
            visibleLines = preferences[landscapeLinesKey] ?: LyricLayoutDefaults.LANDSCAPE_VISIBLE_LINES,
            lineSpacingDp = preferences[landscapeLineSpacingKey] ?: LyricLayoutDefaults.LINE_SPACING_DEFAULT_DP,
            threeDIntensity = preferences[landscape3dKey] ?: LyricLayoutDefaults.LANDSCAPE_3D_INTENSITY,
            fadeLines = snapHalfLines(
                preferences[landscapeFadeKey] ?: LyricLayoutDefaults.FADE_LINES_DEFAULT,
                LyricLayoutDefaults.FADE_LINES_MIN,
                LyricLayoutDefaults.FADE_LINES_MAX,
            ),
            fadeInsetLines = snapHalfLines(
                preferences[landscapeFadeInsetKey] ?: LyricLayoutDefaults.FADE_INSET_LINES_DEFAULT,
                LyricLayoutDefaults.FADE_INSET_LINES_MIN,
                LyricLayoutDefaults.FADE_INSET_LINES_MAX,
            ),
        )
    }

suspend fun Context.saveHomePortraitLyricLayout(
    fontSizeSp: Int,
    visibleLines: Int,
    lineSpacingDp: Int,
    alignment: LyricAlignment,
    fadeLines: Float,
    fadeInsetLines: Float,
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
        preferences[homePortraitFadeKey] = snapHalfLines(
            fadeLines,
            LyricLayoutDefaults.FADE_LINES_MIN,
            LyricLayoutDefaults.FADE_LINES_MAX,
        )
        preferences[homePortraitFadeInsetKey] = snapHalfLines(
            fadeInsetLines,
            LyricLayoutDefaults.FADE_INSET_LINES_MIN,
            LyricLayoutDefaults.FADE_INSET_LINES_MAX,
        )
    }
}

suspend fun Context.saveLandscapeLyricLayout(
    fontSizeSp: Int,
    visibleLines: Int,
    lineSpacingDp: Int,
    threeDIntensity: Float,
    fadeLines: Float,
    fadeInsetLines: Float,
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
        preferences[landscapeFadeKey] = snapHalfLines(
            fadeLines,
            LyricLayoutDefaults.FADE_LINES_MIN,
            LyricLayoutDefaults.FADE_LINES_MAX,
        )
        preferences[landscapeFadeInsetKey] = snapHalfLines(
            fadeInsetLines,
            LyricLayoutDefaults.FADE_INSET_LINES_MIN,
            LyricLayoutDefaults.FADE_INSET_LINES_MAX,
        )
    }
}

private fun nearestOddPreset(value: Int, presets: List<Int>): Int =
    presets.minByOrNull { kotlin.math.abs(it - value) } ?: value

// 未知取值回落默认对齐，避免读取中断
private fun String?.toLyricAlignment(): LyricAlignment =
    LyricAlignment.entries.firstOrNull { it.name == this } ?: LyricLayoutDefaults.HOME_PORTRAIT_ALIGNMENT

// 按半行取整并夹区间，读写共用
private fun snapHalfLines(value: Float, min: Float, max: Float): Float {
    val step = LyricLayoutDefaults.FADE_STEP_LINES
    return ((value / step).roundToInt() * step).coerceIn(min, max)
}

// 避免越界写入或落成非整步值
private fun snapLineSpacing(value: Int): Int {
    val min = LyricLayoutDefaults.LINE_SPACING_MIN_DP
    val max = LyricLayoutDefaults.LINE_SPACING_MAX_DP
    val step = LyricLayoutDefaults.LINE_SPACING_STEP_DP
    val clamped = value.coerceIn(min, max)
    return (((clamped - min) / step) * step + min).coerceIn(min, max)
}
