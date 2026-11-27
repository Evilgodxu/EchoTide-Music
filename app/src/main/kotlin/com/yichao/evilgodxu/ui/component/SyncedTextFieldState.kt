package com.yichao.evilgodxu.ui.component

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow

/**
 * 受控输入框状态：输入回写外部，外部改写同步回框内。
 * 内容一致时不回写，避免光标跳到末尾。
 */
@Composable
internal fun rememberSyncedTextFieldState(
    text: String,
    onTextChange: (String) -> Unit,
): TextFieldState {
    val state = rememberTextFieldState(text)
    val currentText by rememberUpdatedState(text)
    val currentOnTextChange by rememberUpdatedState(onTextChange)
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }.collect { value ->
            if (value != currentText) currentOnTextChange(value)
        }
    }
    LaunchedEffect(state) {
        snapshotFlow { currentText }.collect { value ->
            if (state.text.toString() != value) state.setTextAndPlaceCursorAtEnd(value)
        }
    }
    return state
}
