package com.yichao.evilgodxu.screens.spectrum.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.R

// 分析中占位：分析按全曲推进且不可跳步，只报进度、不提供取消入口
@Composable
internal fun SpectrumProgress(progress: Float, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            progress = { progress },
            modifier = Modifier.size(36.dp),
            strokeWidth = 2.dp,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.spectrum_generating),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "${(progress * 100).toInt()}%",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
        )
    }
}

// 不可分析占位：音频不可解码或源文件已不可访问
@Composable
internal fun SpectrumUnavailable(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.spectrum_unavailable),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
    }
}
