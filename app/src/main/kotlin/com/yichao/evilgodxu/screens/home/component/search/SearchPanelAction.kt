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

// 图标 15dp：不高于同行 12sp 文字
private val ActionIconSize = 15.dp
// 圆角 8dp，仅作点按反馈范围
private val ActionShape = RoundedCornerShape(8.dp)
// 常态 85% 白，避免抢过搜索内容
private val ActionColor = Color.White.copy(alpha = 0.85f)
// 不可用态降透明度，不换色
private val ActionDisabledColor = Color.White.copy(alpha = 0.4f)

// 动作按钮：清空与刷新共用，样式只此处定义
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
            // 含义由右侧文字承担，图标不重复发音
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
