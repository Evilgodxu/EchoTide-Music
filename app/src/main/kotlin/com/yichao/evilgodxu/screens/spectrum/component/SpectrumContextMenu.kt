package com.yichao.evilgodxu.screens.spectrum.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.ui.component.menuEdgePositionProvider

// 频谱图长按菜单：锚定频谱图下缘弹出，提供导出图片的两条路径
@Composable
internal fun SpectrumContextMenu(
    visible: Boolean,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    Popup(
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
        popupPositionProvider = menuEdgePositionProvider(atTop = false),
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 4.dp,
        ) {
            Row(horizontalArrangement = Arrangement.Center) {
                SpectrumMenuItem(
                    text = stringResource(R.string.spectrum_menu_share_image),
                    onClick = onShare,
                )
                SpectrumMenuItem(
                    text = stringResource(R.string.spectrum_menu_save_image),
                    onClick = onSave,
                )
            }
        }
    }
}

// 菜单项：与播放器长按菜单同一呈现（次要底色 + 主色文字）
@Composable
private fun SpectrumMenuItem(text: String, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(6.dp), color = Color.Transparent, onClick = onClick) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }
}
