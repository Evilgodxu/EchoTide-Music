package com.yichao.evilgodxu.screens.typography

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yichao.evilgodxu.data.settings.homePortraitLyricLayoutFlow
import com.yichao.evilgodxu.data.settings.landscapeLyricLayoutFlow
import com.yichao.evilgodxu.data.settings.LandscapeLyricLayoutParams
import com.yichao.evilgodxu.data.settings.LyricAlignment
import com.yichao.evilgodxu.data.settings.LyricLayoutDefaults
import com.yichao.evilgodxu.data.settings.LyricLayoutParams
import com.yichao.evilgodxu.data.settings.saveHomePortraitLyricLayout
import com.yichao.evilgodxu.data.settings.saveLandscapeLyricLayout
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// 播放页排版 ViewModel：汇总各场景排版参数，调节时本地即时更新并落盘
class TypographyViewModel(application: Application) : AndroidViewModel(application) {

    private val context get() = getApplication<Application>()

    private val _uiState = MutableStateFlow(TypographyUiState())
    val uiState: StateFlow<TypographyUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            context.homePortraitLyricLayoutFlow().collect { layout ->
                _uiState.update { it.copy(homePortrait = layout) }
            }
        }
        viewModelScope.launch {
            context.landscapeLyricLayoutFlow().collect { layout ->
                _uiState.update { it.copy(homeLandscape = layout) }
            }
        }
    }

    fun adjustHomePortraitFontSize(delta: Int) {
        val current = _uiState.value.homePortrait
        applyHomePortrait(current.copy(fontSizeSp = adjustFontSize(current.fontSizeSp, delta)))
    }

    fun adjustHomePortraitLines(delta: Int) {
        val current = _uiState.value.homePortrait
        applyHomePortrait(
            current.copy(
                visibleLines = adjustLines(
                    LyricLayoutDefaults.HOME_PORTRAIT_LINE_PRESETS, current.visibleLines, delta,
                ),
            ),
        )
    }

    fun adjustHomePortraitLineSpacing(delta: Int) {
        val current = _uiState.value.homePortrait
        applyHomePortrait(current.copy(lineSpacingDp = adjustLineSpacing(current.lineSpacingDp, delta)))
    }

    fun selectHomePortraitAlignment(alignment: LyricAlignment) {
        applyHomePortrait(_uiState.value.homePortrait.copy(alignment = alignment))
    }

    fun adjustHomePortraitFade(delta: Int) {
        val current = _uiState.value.homePortrait
        applyHomePortrait(
            current.copy(
                fadeLines = stepHalfLines(
                    current.fadeLines, delta,
                    LyricLayoutDefaults.FADE_LINES_MIN, LyricLayoutDefaults.FADE_LINES_MAX,
                ),
            ),
        )
    }

    fun adjustHomePortraitFadeInset(delta: Int) {
        val current = _uiState.value.homePortrait
        applyHomePortrait(
            current.copy(
                fadeInsetLines = stepHalfLines(
                    current.fadeInsetLines, delta,
                    LyricLayoutDefaults.FADE_INSET_LINES_MIN, LyricLayoutDefaults.FADE_INSET_LINES_MAX,
                ),
            ),
        )
    }

    fun adjustLandscapeFontSize(delta: Int) {
        val current = _uiState.value.homeLandscape
        applyLandscape(current.copy(fontSizeSp = adjustFontSize(current.fontSizeSp, delta)))
    }

    fun adjustLandscapeLines(delta: Int) {
        val current = _uiState.value.homeLandscape
        applyLandscape(
            current.copy(
                visibleLines = adjustLines(
                    LyricLayoutDefaults.LANDSCAPE_LINE_PRESETS, current.visibleLines, delta,
                ),
            ),
        )
    }

    fun adjustLandscapeLineSpacing(delta: Int) {
        val current = _uiState.value.homeLandscape
        applyLandscape(current.copy(lineSpacingDp = adjustLineSpacing(current.lineSpacingDp, delta)))
    }

    fun adjustLandscape3D(delta: Int) {
        val current = _uiState.value.homeLandscape
        val steps = ((LyricLayoutDefaults.THREE_D_MAX - LyricLayoutDefaults.THREE_D_MIN) /
                LyricLayoutDefaults.THREE_D_STEP).toInt()
        val currentStep = ((current.threeDIntensity - LyricLayoutDefaults.THREE_D_MIN) /
                LyricLayoutDefaults.THREE_D_STEP).roundToInt()
        val next = LyricLayoutDefaults.THREE_D_MIN +
                (currentStep + delta).coerceIn(0, steps) * LyricLayoutDefaults.THREE_D_STEP
        applyLandscape(current.copy(threeDIntensity = next))
    }

    fun adjustLandscapeFade(delta: Int) {
        val current = _uiState.value.homeLandscape
        applyLandscape(
            current.copy(
                fadeLines = stepHalfLines(
                    current.fadeLines, delta,
                    LyricLayoutDefaults.FADE_LINES_MIN, LyricLayoutDefaults.FADE_LINES_MAX,
                ),
            ),
        )
    }

    fun adjustLandscapeFadeInset(delta: Int) {
        val current = _uiState.value.homeLandscape
        applyLandscape(
            current.copy(
                fadeInsetLines = stepHalfLines(
                    current.fadeInsetLines, delta,
                    LyricLayoutDefaults.FADE_INSET_LINES_MIN, LyricLayoutDefaults.FADE_INSET_LINES_MAX,
                ),
            ),
        )
    }

    // 竖屏各调节入口共用同一条落盘路径：整份排版参数一次性提交，避免某个字段取错当前值
    private fun applyHomePortrait(layout: LyricLayoutParams) {
        _uiState.update { it.copy(homePortrait = layout) }
        viewModelScope.launch {
            context.saveHomePortraitLyricLayout(
                layout.fontSizeSp,
                layout.visibleLines,
                layout.lineSpacingDp,
                layout.alignment,
                layout.fadeLines,
                layout.fadeInsetLines,
            )
        }
    }

    // 横屏同理：字号、行数、行间距、3D 强度与渐隐一起整份提交
    private fun applyLandscape(layout: LandscapeLyricLayoutParams) {
        _uiState.update { it.copy(homeLandscape = layout) }
        viewModelScope.launch {
            context.saveLandscapeLyricLayout(
                layout.fontSizeSp,
                layout.visibleLines,
                layout.lineSpacingDp,
                layout.threeDIntensity,
                layout.fadeLines,
                layout.fadeInsetLines,
            )
        }
    }

    private fun adjustFontSize(current: Int, delta: Int): Int =
        (current + delta).coerceIn(LyricLayoutDefaults.FONT_SIZE_MIN_SP, LyricLayoutDefaults.FONT_SIZE_MAX_SP)

    private fun adjustLines(presets: List<Int>, current: Int, delta: Int): Int {
        val index = presets.indexOf(current)
        if (index < 0) return current
        return presets[(index + delta).coerceIn(0, presets.lastIndex)]
    }

    private fun adjustLineSpacing(current: Int, delta: Int): Int =
        (current + delta * LyricLayoutDefaults.LINE_SPACING_STEP_DP).coerceIn(
            LyricLayoutDefaults.LINE_SPACING_MIN_DP,
            LyricLayoutDefaults.LINE_SPACING_MAX_DP,
        )

    // 半行档位步进：先折算成档数再加减，反复调节不会因浮点累加漂到档位之外
    private fun stepHalfLines(current: Float, delta: Int, min: Float, max: Float): Float {
        val step = LyricLayoutDefaults.FADE_STEP_LINES
        return (((current / step).roundToInt() + delta) * step).coerceIn(min, max)
    }
}