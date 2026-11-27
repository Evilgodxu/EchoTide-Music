package com.yichao.evilgodxu.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.Rect
import android.view.ViewTreeObserver
import com.yichao.evilgodxu.ui.icons.AppIcons

// 搜索框占位 54dp，供列表底部留白
internal val SEARCH_BAR_REGION_DP = 54.dp

// 操作区与搜索框间距
internal val SEARCH_ACTION_GAP_DP = 6.dp

// 搜索条底色与描边透明度
internal const val SEARCH_BAR_FILL_ALPHA = 0.08f
internal const val SEARCH_BAR_BORDER_ALPHA = 0.22f

// 可见区缩减超 15% 视为键盘弹出
private const val KEYBOARD_VISIBLE_RATIO = 0.15f

// 底部常驻搜索框，列表底部按占位留白
@Composable
internal fun BoxScope.BottomSearchBarOverlay(
    placeholder: String,
    query: String,
    onQueryChange: (String) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    fillColor: Color? = null,
    borderColor: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    hintColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    actions: (@Composable () -> Unit)? = null,
    // 输入态隐藏操作按钮，释放高度
    actionsVisible: Boolean = true,
) {
    Column(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
    ) {
        if (actions != null) {
            // 操作区右对齐，输入态整组隐藏
            AnimatedVisibility(
                visible = actionsVisible,
                enter = fadeIn(animationSpec = tween(120)) +
                    slideInVertically(animationSpec = tween(120)) { it },
                exit = fadeOut(animationSpec = tween(120)) +
                    slideOutVertically(animationSpec = tween(120)) { it },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = SEARCH_ACTION_GAP_DP),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    actions()
                }
            }
        }
        SearchInputBar(
            placeholder = placeholder,
            query = query,
            onQueryChange = onQueryChange,
            onFocusChanged = onFocusChanged,
            fillColor = fillColor,
            borderColor = borderColor,
            textColor = textColor,
            hintColor = hintColor,
        )
    }
}

// 胶囊搜索框；深色面板可传入浅色填充
@Composable
internal fun SearchInputBar(
    placeholder: String,
    query: String,
    onQueryChange: (String) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    fillColor: Color? = null,
    borderColor: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    hintColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val textState = rememberSyncedTextFieldState(query, onQueryChange)
    // 键盘收起时主动释放残留焦点
    var fieldFocused by remember { mutableStateOf(false) }
    // 按可见区高度差判定键盘显隐
    val view = LocalView.current
    DisposableEffect(view) {
        val rootView = view.rootView
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            val rect = Rect()
            view.getWindowVisibleDisplayFrame(rect)
            val heightDiff = rootView.height - rect.height()
            val keyboardVisible = heightDiff > (rootView.height * KEYBOARD_VISIBLE_RATIO)
            if (!keyboardVisible && fieldFocused) {
                focusManager.clearFocus()
            }
        }
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        onDispose { view.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(fillColor ?: Color.Transparent)
            .border(
                width = 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(22.dp),
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = AppIcons.Search,
                contentDescription = null,
                tint = hintColor,
                modifier = Modifier.size(18.dp),
            )
            BasicTextField(
                state = textState,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp)
                    .onFocusChanged { focused ->
                        fieldFocused = focused.isFocused
                        onFocusChanged(focused.isFocused)
                    },
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = textColor,
                    fontSize = 13.sp,
                ),
                cursorBrush = SolidColor(textColor),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                onKeyboardAction = KeyboardActionHandler {
                    // 回车后收起键盘并释放焦点
                    keyboardController?.hide()
                    focusManager.clearFocus()
                },
                lineLimits = TextFieldLineLimits.SingleLine,
                decorator = TextFieldDecorator { innerTextField ->
                    Box {
                        if (textState.text.isEmpty()) {
                            Text(
                                text = placeholder,
                                color = hintColor,
                                fontSize = 14.sp,
                            )
                        }
                        innerTextField()
                    }
                },
            )
            if (textState.text.isNotEmpty()) {
                IconButton(
                    onClick = { onQueryChange("") },
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(
                        imageVector = AppIcons.Close,
                        contentDescription = null,
                        tint = hintColor,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}
