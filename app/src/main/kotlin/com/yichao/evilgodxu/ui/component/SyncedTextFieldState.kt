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
 * 文本仍由调用方持有的输入框状态：用户输入经 [snapshotFlow] 回写外部值，
 * 外部改写（如清空按钮）再同步回输入框。
 *
 * 两侧内容一致时不回写，避免把光标重置到末尾；因此该状态既承接用户输入，
 * 也能跟随 ViewModel / 页面状态的外部变更。
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
