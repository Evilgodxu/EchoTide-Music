package com.yichao.evilgodxu.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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

// 底部搜索框在列表末尾占用的区域高度：最后一项底缘进入该区域即判定为滚到底部
internal val SEARCH_BAR_REGION_DP = 54.dp

// 搜索框上方悬浮操作区与搜索框的间距：调用方计算底部遮挡区域高度时需一并计入
internal val SEARCH_ACTION_GAP_DP = 6.dp

// 判定软键盘可见的高度差比例：可见区域较根视图缩减超过该比例即视为键盘弹出
private const val KEYBOARD_VISIBLE_RATIO = 0.15f

// 悬浮在列表底部的搜索输入框：列表滚动中或滚到底部时隐藏，避免遮挡末尾条目；输入/聚焦期间常驻。
// actions 为输入框右上方的悬浮操作区，与输入框同处一个显隐容器，显隐动画由结构保证同步
@Composable
internal fun BoxScope.BottomSearchBarOverlay(
    hidden: Boolean,
    placeholder: String,
    query: String,
    onQueryChange: (String) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    borderColor: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    hintColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    actions: (@Composable () -> Unit)? = null,
    // 搜索框聚焦进入输入态时隐藏操作按钮，释放按钮占用的高度空间
    actionsVisible: Boolean = true,
) {
    AnimatedVisibility(
        visible = !hidden,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
        enter = fadeIn(animationSpec = tween(160)) +
            slideInVertically(animationSpec = tween(160)) { it },
        exit = fadeOut(animationSpec = tween(160)) +
            slideOutVertically(animationSpec = tween(160)) { it },
    ) {
        Column {
            if (actions != null) {
                // 操作区右对齐，与输入框右缘对齐；聚焦进入输入态时整组隐藏，释放占位高度
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
                borderColor = borderColor,
                textColor = textColor,
                hintColor = hintColor,
            )
        }
    }
}

// 列表内搜索输入框：胶囊描边样式，输入即回调过滤。
// 前景配色默认跟随主题；底色为深色沉浸背景的面板需传入白色系，避免深色主题色压在深底上不可辨
@Composable
internal fun SearchInputBar(
    placeholder: String,
    query: String,
    onQueryChange: (String) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    borderColor: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    hintColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    // 记录输入框自身焦点：键盘收起但焦点仍残留时（系统返回键/手势收键盘）需主动释放，
    // 否则上层拦截层持续生效、列表无法滚动，「置顶」「定位」按钮也一直隐藏
    var fieldFocused by remember { mutableStateOf(false) }
    // 键盘收起瞬间释放输入焦点：以可见区域高度差判定软键盘显隐
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
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp)
                    .onFocusChanged { focused ->
                        fieldFocused = focused.isFocused
                        onFocusChanged(focused.isFocused)
                    },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = textColor,
                    fontSize = 13.sp,
                ),
                cursorBrush = SolidColor(textColor),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    // 回车搜索后收起键盘并释放焦点，避免输入框保持聚焦态
                    keyboardController?.hide()
                    focusManager.clearFocus()
                }),
                decorationBox = { innerTextField ->
                    Box {
                        if (query.isEmpty()) {
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
            if (query.isNotEmpty()) {
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
