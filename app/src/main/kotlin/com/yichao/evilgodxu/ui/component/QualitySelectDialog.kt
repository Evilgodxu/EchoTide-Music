package com.yichao.evilgodxu.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.data.music.api.MusicQuality

// 复用音质选择对话框：在线搜索与导入歌单等场景供用户自行决定音质，点击选项后由调用方回调处理
@Composable
internal fun QualitySelectDialog(
    title: String,
    onSelect: (MusicQuality) -> Unit,
    onDismiss: () -> Unit,
) {
    DialogCard(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            MusicQuality.entries.forEach { quality ->
                QualityOptionCard(
                    label = stringResource(
                        when (quality) {
                            MusicQuality.LOSSLESS -> R.string.music_quality_lossless
                            MusicQuality.HIGH -> R.string.music_quality_high
                            MusicQuality.STANDARD -> R.string.music_quality_standard
                        }
                    ),
                    onClick = { onSelect(quality) },
                )
            }
        }
    }
}

// 音质选项卡片，样式与代理音源导入方式选项一致；enabled=false 用于尝试中整体禁用
@Composable
internal fun QualityOptionCard(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Text(
        text = label,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 14.dp),
        textAlign = TextAlign.Center,
        color = if (enabled) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
    )
}