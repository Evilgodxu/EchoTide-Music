package com.yichao.evilgodxu.screens.typography

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yichao.evilgodxu.data.settings.homePortraitLyricLayoutFlow
import com.yichao.evilgodxu.data.settings.landscapeLyricLayoutFlow
import com.yichao.evilgodxu.data.settings.LyricLayoutDefaults
import com.yichao.evilgodxu.data.settings.musicPanelLyricLayoutFlow
import com.yichao.evilgodxu.data.settings.saveHomePortraitLyricLayout
import com.yichao.evilgodxu.data.settings.saveLandscapeLyricLayout
import com.yichao.evilgodxu.data.settings.saveMusicPanelLyricLayout
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// 歌词排版 ViewModel：汇总三场景排版参数，调节时本地即时更新并落盘
class TypographyViewModel(application: Application) : AndroidViewModel(application) {

    private val context get() = getApplication<Application>()

    private val _uiState = MutableStateFlow(TypographyUiState())
    val uiState: StateFlow<TypographyUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            context.musicPanelLyricLayoutFlow().collect { layout ->
                _uiState.update { it.copy(musicPanel = layout) }
            }
        }
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

    fun adjustMusicPanelFontSize(delta: Int) {
        val state = _uiState.value
        val next = adjustFontSize(state.musicPanel.fontSizeSp, delta)
        _uiState.update { it.copy(musicPanel = it.musicPanel.copy(fontSizeSp = next)) }
        viewModelScope.launch {
            context.saveMusicPanelLyricLayout(
                next,
                _uiState.value.musicPanel.visibleLines,
                _uiState.value.musicPanel.lineSpacingDp,
            )
        }
    }

    fun adjustMusicPanelLines(delta: Int) {
        val state = _uiState.value
        val next = adjustLines(LyricLayoutDefaults.PORTRAIT_LINE_PRESETS, state.musicPanel.visibleLines, delta)
        _uiState.update { it.copy(musicPanel = it.musicPanel.copy(visibleLines = next)) }
        viewModelScope.launch {
            context.saveMusicPanelLyricLayout(
                _uiState.value.musicPanel.fontSizeSp,
                next,
                _uiState.value.musicPanel.lineSpacingDp,
            )
        }
    }

    fun adjustMusicPanelLineSpacing(delta: Int) {
        val state = _uiState.value
        val next = adjustLineSpacing(state.musicPanel.lineSpacingDp, delta)
        _uiState.update { it.copy(musicPanel = it.musicPanel.copy(lineSpacingDp = next)) }
        viewModelScope.launch {
            context.saveMusicPanelLyricLayout(
                _uiState.value.musicPanel.fontSizeSp,
                _uiState.value.musicPanel.visibleLines,
                next,
            )
        }
    }

    fun adjustHomePortraitFontSize(delta: Int) {
        val state = _uiState.value
        val next = adjustFontSize(state.homePortrait.fontSizeSp, delta)
        _uiState.update { it.copy(homePortrait = it.homePortrait.copy(fontSizeSp = next)) }
        viewModelScope.launch {
            context.saveHomePortraitLyricLayout(
                next,
                _uiState.value.homePortrait.visibleLines,
                _uiState.value.homePortrait.lineSpacingDp,
            )
        }
    }

    fun adjustHomePortraitLines(delta: Int) {
        val state = _uiState.value
        val next = adjustLines(LyricLayoutDefaults.HOME_PORTRAIT_LINE_PRESETS, state.homePortrait.visibleLines, delta)
        _uiState.update { it.copy(homePortrait = it.homePortrait.copy(visibleLines = next)) }
        viewModelScope.launch {
            context.saveHomePortraitLyricLayout(
                _uiState.value.homePortrait.fontSizeSp,
                next,
                _uiState.value.homePortrait.lineSpacingDp,
            )
        }
    }

    fun adjustHomePortraitLineSpacing(delta: Int) {
        val state = _uiState.value
        val next = adjustLineSpacing(state.homePortrait.lineSpacingDp, delta)
        _uiState.update { it.copy(homePortrait = it.homePortrait.copy(lineSpacingDp = next)) }
        viewModelScope.launch {
            context.saveHomePortraitLyricLayout(
                _uiState.value.homePortrait.fontSizeSp,
                _uiState.value.homePortrait.visibleLines,
                next,
            )
        }
    }

    fun adjustLandscapeFontSize(delta: Int) {
        val state = _uiState.value
        val next = adjustFontSize(state.homeLandscape.fontSizeSp, delta)
        _uiState.update { it.copy(homeLandscape = it.homeLandscape.copy(fontSizeSp = next)) }
        viewModelScope.launch {
            context.saveLandscapeLyricLayout(
                next,
                _uiState.value.homeLandscape.visibleLines,
                _uiState.value.homeLandscape.lineSpacingDp,
                _uiState.value.homeLandscape.threeDIntensity,
            )
        }
    }

    fun adjustLandscapeLines(delta: Int) {
        val state = _uiState.value
        val next = adjustLines(LyricLayoutDefaults.LANDSCAPE_LINE_PRESETS, state.homeLandscape.visibleLines, delta)
        _uiState.update { it.copy(homeLandscape = it.homeLandscape.copy(visibleLines = next)) }
        viewModelScope.launch {
            context.saveLandscapeLyricLayout(
                _uiState.value.homeLandscape.fontSizeSp,
                next,
                _uiState.value.homeLandscape.lineSpacingDp,
                _uiState.value.homeLandscape.threeDIntensity,
            )
        }
    }

    fun adjustLandscapeLineSpacing(delta: Int) {
        val state = _uiState.value
        val next = adjustLineSpacing(state.homeLandscape.lineSpacingDp, delta)
        _uiState.update { it.copy(homeLandscape = it.homeLandscape.copy(lineSpacingDp = next)) }
        viewModelScope.launch {
            context.saveLandscapeLyricLayout(
                _uiState.value.homeLandscape.fontSizeSp,
                _uiState.value.homeLandscape.visibleLines,
                next,
                _uiState.value.homeLandscape.threeDIntensity,
            )
        }
    }

    fun adjustLandscape3D(delta: Int) {
        val state = _uiState.value
        val steps = ((LyricLayoutDefaults.THREE_D_MAX - LyricLayoutDefaults.THREE_D_MIN) /
                LyricLayoutDefaults.THREE_D_STEP).toInt()
        val currentStep = ((state.homeLandscape.threeDIntensity - LyricLayoutDefaults.THREE_D_MIN) /
                LyricLayoutDefaults.THREE_D_STEP).roundToInt()
        val next = LyricLayoutDefaults.THREE_D_MIN +
                (currentStep + delta).coerceIn(0, steps) * LyricLayoutDefaults.THREE_D_STEP
        _uiState.update { it.copy(homeLandscape = it.homeLandscape.copy(threeDIntensity = next)) }
        viewModelScope.launch {
            context.saveLandscapeLyricLayout(
                _uiState.value.homeLandscape.fontSizeSp,
                _uiState.value.homeLandscape.visibleLines,
                _uiState.value.homeLandscape.lineSpacingDp,
                next,
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
}