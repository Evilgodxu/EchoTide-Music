package com.yichao.evilgodxu.ui.component

import android.os.SystemClock
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.ui.icons.AppIcons

// 二级页标题栏，返回按钮防抖
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageTopBar(title: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    // 记录上次点击，400ms 内忽略
    var lastBackClickAt by remember { mutableLongStateOf(0L) }
    TopAppBar(
        modifier = modifier,
        title = { Text(title) },
        windowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Top),
        navigationIcon = {
            IconButton(onClick = {
                val now = SystemClock.elapsedRealtime()
                if (now - lastBackClickAt > 400L) {
                    lastBackClickAt = now
                    onBack()
                }
            }) {
                Icon(AppIcons.ChevronLeft, stringResource(R.string.back))
            }
        },
    )
}
