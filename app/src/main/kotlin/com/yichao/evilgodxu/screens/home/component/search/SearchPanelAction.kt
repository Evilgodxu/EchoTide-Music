package com.yichao.evilgodxu.screens.home.component.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 面板动作按钮的图标尺寸：与按钮文字同处一行，取 15dp 使其不高于 12sp 文字的视觉重量
private val ActionIconSize = 15.dp
// 图标与文字共用的圆角：只作点按反馈的范围提示，不需要贴合文字外形的精确形状
private val ActionShape = RoundedCornerShape(8.dp)
// 常态配色：不用纯白，与标签文字同档，避免动作按钮抢过搜索内容本身
private val ActionColor = Color.White.copy(alpha = 0.85f)
// 不可用态（如刷新进行中）：整体降透明度而非换色，保持同一色系
private val ActionDisabledColor = Color.White.copy(alpha = 0.4f)

// 搜索面板内的动作按钮：历史的「清空」与结果的「刷新」共用。
// 图标尺寸、配色与内边距只在此定义一处 —— 两处各写一遍，随后必然走形
@Composable
internal fun SearchPanelAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val color = if (enabled) ActionColor else ActionDisabledColor
    Row(
        modifier = modifier
            .clip(ActionShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            // 动作含义由右侧文字承担，图标对无障碍不重复发音
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(ActionIconSize),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            color = color,
            fontSize = 12.sp,
        )
    }
}
