package com.yichao.evilgodxu.screens.settings.component.entry

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.theme.AppAlpha
import com.yichao.evilgodxu.theme.AppSpacing

/** 设置项：可点击，可回传点击坐标驱动动效 */
@Composable
fun SettingsEntry(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit = {},
    onClickWithPosition: ((Offset) -> Unit)? = null,
) {
    var currentCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClickWithPosition == null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                        .onGloballyPositioned { currentCoordinates = it }
                        .pointerInput(onClickWithPosition) {
                            detectTapGestures { offset ->
                                onClickWithPosition(currentCoordinates?.localToRoot(offset) ?: offset)
                            }
                        }
                },
            )
            .padding(horizontal = AppSpacing.RowHorizontal, vertical = AppSpacing.RowVertical),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = AppAlpha.Secondary))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) {
                Text(
                    subtitle,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = AppAlpha.Secondary),
                )
            }
        }
    }
}
